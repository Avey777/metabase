(ns metabase.setup.api
  (:require
   [java-time.api :as t]
   [metabase.analytics.core :as analytics]
   [metabase.api.common :as api]
   [metabase.api.common.validation :as validation]
   [metabase.api.macros :as api.macros]
   [metabase.channel.email :as email]
   [metabase.config :as config]
   [metabase.db :as mdb]
   [metabase.embed.settings :as embed.settings]
   [metabase.events :as events]
   [metabase.integrations.slack :as slack]
   [metabase.models.interface :as mi]
   [metabase.models.setting :as setting]
   [metabase.models.setting.cache :as setting.cache]
   [metabase.models.user :as user]
   [metabase.permissions.core :as perms]
   [metabase.premium-features.core :as premium-features]
   [metabase.public-settings :as public-settings]
   [metabase.request.core :as request]
   [metabase.session.models.session :as session]
   [metabase.setup.core :as setup]
   [metabase.util :as u]
   [metabase.util.i18n :as i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(def ^:private ^:deprcated SetupToken
  "Schema for a string that matches the instance setup token."
  (mu/with-api-error-message
   [:and
    ms/NonBlankString
    [:fn
     {:error/message "setup token"}
     (every-pred string? #'setup/token-match?)]]
   (i18n/deferred-tru "Token does not match the setup token.")))

(def ^:dynamic ^:private *allow-api-setup-after-first-user-is-created*
  "We must not allow users to setup multiple super users after the first user is created. But tests still need to be able
  to. This var is redef'd to false by certain tests to allow that."
  false)

(defn- setup-create-user! [{:keys [email first-name last-name password device-info]}]
  (when (and (setup/has-user-setup)
             (not *allow-api-setup-after-first-user-is-created*))
    ;; many tests use /api/setup to setup multiple users, so *allow-api-setup-after-first-user-is-created* is
    ;; redefined by them
    (throw (ex-info
            (tru "The /api/setup route can only be used to create the first user, however a user currently exists.")
            {:status-code 403})))
  (let [new-user   (first (t2/insert-returning-instances! :model/User
                                                          :email        email
                                                          :first_name   first-name
                                                          :last_name    last-name
                                                          :password     (str (random-uuid))
                                                          :is_superuser true))
        user-id    (u/the-id new-user)]
    ;; this results in a second db call, but it avoids redundant password code so figure it's worth it
    (user/set-password! user-id password)
    ;; then we create a session right away because we want our new user logged in to continue the setup process
    (let [session (session/create-session! :password new-user device-info)]
      ;; return user ID, session ID, and the Session object itself
      {:session-key (:key session), :user-id user-id, :session session})))

(defn- setup-maybe-create-and-invite-user! [{:keys [email] :as user}, invitor]
  (when email
    (if-not (email/email-configured?)
      (log/error "Could not invite user because email is not configured.")
      (u/prog1 (user/insert-new-user! user)
        (user/set-permissions-groups! <> [(perms/all-users-group) (perms/admin-group)])
        (events/publish-event! :event/user-invited
                               {:object
                                (assoc <>
                                       :is_from_setup true
                                       :invite_method "email"
                                       :sso_source    (:sso_source <>))
                                :details {:invitor (select-keys invitor [:email :first_name])}})
        (analytics/track-event! :snowplow/invite
                                {:event           :invite-sent
                                 :invited-user-id (u/the-id <>)
                                 :source          "setup"})))))

(defn- setup-set-settings! [{:keys [email site-name site-locale]}]
  ;; set a couple preferences
  (public-settings/site-name! site-name)
  (public-settings/admin-email! email)
  (when site-locale
    (public-settings/site-locale! site-locale))
  ;; default to `true` the setting will set itself correctly whether a boolean or boolean string is specified
  (public-settings/anon-tracking-enabled! true))

(api.macros/defendpoint :post "/"
  "Special endpoint for creating the first user during setup. This endpoint both creates the user AND logs them in and
  returns a session ID. This endpoint can also be used to add a database, create and invite a second admin, and/or
  set specific settings from the setup flow."
  [_route-params
   _query-params
   {{first-name :first_name, last-name :last_name, :keys [email password]} :user
    {invited-first-name :first_name
     invited-last-name  :last_name
     invited-email      :email} :invite
    {site-name :site_name
     site-locale :site_locale} :prefs}
   :- [:map
       [:token SetupToken]
       [:user [:map
               [:email      ms/Email]
               [:password   ms/ValidPassword]
               [:first_name {:optional true} [:maybe ms/NonBlankString]]
               [:last_name  {:optional true} [:maybe ms/NonBlankString]]]]
       [:invite {:optional true} [:map
                                  [:first_name {:optional true} [:maybe ms/NonBlankString]]
                                  [:last_name  {:optional true} [:maybe ms/NonBlankString]]
                                  [:email      {:optional true} [:maybe ms/Email]]]]
       [:prefs [:map
                [:site_name   ms/NonBlankString]
                [:site_locale {:optional true} [:maybe ms/ValidLocale]]]]]
   request]
  (letfn [(create! []
            (try
              (t2/with-transaction []
                (let [user-info (setup-create-user! {:email email
                                                     :first-name first-name
                                                     :last-name last-name
                                                     :password password
                                                     :device-info (request/device-info request)})]
                  (setup-maybe-create-and-invite-user! {:email invited-email
                                                        :first_name invited-first-name
                                                        :last_name invited-last-name}
                                                       {:email email, :first_name first-name})
                  (setup-set-settings! {:email email :site-name site-name :site-locale site-locale})
                  user-info))
              (catch Throwable e
                ;; if the transaction fails, restore the Settings cache from the DB again so any changes made in this
                ;; endpoint (such as clearing the setup token) are reverted. We can't use `dosync` here to accomplish
                ;; this because there is `io!` in this block
                (setting.cache/restore-cache!)
                (throw e))))]
    (let [{:keys [user-id session-key session]} (create!)
          superuser (t2/select-one :model/User :id user-id)]
      (events/publish-event! :event/user-login {:user-id user-id})
      (when-not (:last_login superuser)
        (events/publish-event! :event/user-joined {:user-id user-id}))
      ;; return response with session ID and set the cookie as well
      (request/set-session-cookies request {:id session-key} session (t/zoned-date-time (t/zone-id "GMT"))))))

;;; Admin Checklist

(def ^:private ChecklistState
  "Malli schema for the state to annotate the checklist."
  [:map {:closed true}
   [:db-type [:enum :h2 :mysql :postgres]]
   [:hosted? :boolean]
   [:embedding [:map
                [:interested? :boolean]
                [:done? :boolean]
                [:app-origin :boolean]]]
   [:configured [:map
                 [:email :boolean]
                 [:slack :boolean]
                 [:sso :boolean]]]
   [:counts [:map
             [:user :int]
             [:card :int]
             [:table :int]]]
   [:exists [:map
             [:model :boolean]
             [:non-sample-db :boolean]
             [:dashboard :boolean]
             [:pulse :boolean]
             [:hidden-table :boolean]
             [:collection :boolean]
             [:embedded-resource :boolean]]]])

(mu/defn- state-for-checklist :- ChecklistState
  []
  {:db-type    (mdb/db-type)
   :hosted?    (premium-features/is-hosted?)
   :embedding  {:interested? (not (= (embed.settings/embedding-homepage) :hidden))
                :done?       (= (embed.settings/embedding-homepage) :dismissed-done)
                :app-origin  (boolean (embed.settings/embedding-app-origins-interactive))}
   :configured {:email (email/email-configured?)
                :slack (slack/slack-configured?)
                :sso   (setting/get :google-auth-enabled)}
   :counts     {:user  (t2/count :model/User {:where (mi/exclude-internal-content-hsql :model/User)})
                :card  (t2/count :model/Card {:where (mi/exclude-internal-content-hsql :model/Card)})
                :table (val (ffirst (t2/query {:select [:%count.*]
                                               :from   [[(t2/table-name :model/Table) :t]]
                                               :join   [[(t2/table-name :model/Database) :d] [:= :d.id :t.db_id]]
                                               :where  (mi/exclude-internal-content-hsql :model/Database :table-alias :d)})))}
   :exists     {:non-sample-db     (t2/exists? :model/Database {:where (mi/exclude-internal-content-hsql :model/Database)})
                :dashboard         (t2/exists? :model/Dashboard {:where (mi/exclude-internal-content-hsql :model/Dashboard)})
                :pulse             (t2/exists? :model/Pulse)
                :hidden-table      (t2/exists? :model/Table {:where [:and
                                                                     [:not= :visibility_type nil]
                                                                     (mi/exclude-internal-content-hsql :model/Table)]})
                :collection        (t2/exists? :model/Collection {:where (mi/exclude-internal-content-hsql :model/Collection)})
                :model             (t2/exists? :model/Card {:where [:and
                                                                    [:= :type "model"]
                                                                    (mi/exclude-internal-content-hsql :model/Card)]})
                :embedded-resource (or (t2/exists? :model/Card :enable_embedding true)
                                       (t2/exists? :model/Dashboard :enable_embedding true))}})

(defn- get-connected-tasks
  [{:keys [configured counts exists embedding] :as _info}]
  [{:title       (tru "Add a database")
    :group       (tru "Get connected")
    :description (tru "Connect to your data so your whole team can start to explore.")
    :link        "/admin/databases/create"
    :completed   (exists :non-sample-db)
    :triggered   :always}
   {:title       (tru "Set up email")
    :group       (tru "Get connected")
    :description (tru "Add email credentials so you can more easily invite team members and get updates via Pulses.")
    :link        "/admin/settings/email"
    :completed   (configured :email)
    :triggered   :always}
   {:title       (tru "Set Slack credentials")
    :group       (tru "Get connected")
    :description (tru "Does your team use Slack? If so, you can send automated updates via dashboard subscriptions.")
    :link        "/admin/settings/notifications/slack"
    :completed   (configured :slack)
    :triggered   :always}
   {:title       (tru "Setup embedding")
    :group       (tru "Get connected")
    :description (tru "Get customizable, flexible, and scalable customer-facing analytics in no time")
    :link        "/admin/settings/embedding-in-other-applications"
    :completed   (or (embedding :done?)
                     (and (configured :sso) (embedding :app-origin))
                     (exists :embedded-resource))
    :triggered   (embedding :interested?)}
   {:title       (tru "Invite team members")
    :group       (tru "Get connected")
    :description (tru "Share answers and data with the rest of your team.")
    :link        "/admin/people/"
    :completed   (> (counts :user) 1)
    :triggered   (or (exists :dashboard)
                     (exists :pulse)
                     (>= (counts :card) 5))}])

(defn- productionize-tasks
  [info]
  [{:title       (tru "Switch to a production-ready app database")
    :group       (tru "Productionize")
    :description (tru "Migrate off of the default H2 application database to PostgreSQL or MySQL")
    :link        "https://www.metabase.com/docs/latest/installation-and-operation/migrating-from-h2"
    :completed   (not= (:db-type info) :h2)
    :triggered   (and (= (:db-type info) :h2) (not (:hosted? info)))}])

(defn- curate-tasks
  [{:keys [counts exists] :as _info}]
  [{:title       (tru "Hide irrelevant tables")
    :group       (tru "Curate your data")
    :description (tru "If your data contains technical or irrelevant info you can hide it.")
    :link        "/admin/datamodel/database"
    :completed   (exists :hidden-table)
    :triggered   (>= (counts :table) 20)}
   {:title       (tru "Organize questions")
    :group       (tru "Curate your data")
    :description (tru "Have a lot of saved questions in {0}? Create collections to help manage them and add context." (tru "Metabase"))
    :link        "/collection/root"
    :completed   (exists :collection)
    :triggered   (>= (counts :card) 30)}
   {:title       (tru "Create a model")
    :group       (tru "Curate your data")
    :description (tru "Set up friendly starting points for your team to explore data")
    :link        "/model/new"
    :completed   (exists :model)
    :triggered   (not (exists :model))}])

(mu/defn- checklist-items
  [info :- ChecklistState]
  (remove nil?
          [{:name  (tru "Get connected")
            :tasks (get-connected-tasks info)}
           (when-not (:hosted? info)
             {:name  (tru "Productionize")
              :tasks (productionize-tasks info)})
           {:name  (tru "Curate your data")
            :tasks (curate-tasks info)}]))

(defn- annotate
  "Add `is_next_step` key to all the `steps` from `admin-checklist`, and ensure `triggered` is a boolean.
  The next step is the *first* step where `:triggered` is `true` and `:completed` is `false`."
  [checklist]
  (let [next-step        (->> checklist
                              (mapcat :tasks)
                              (filter (every-pred :triggered (complement :completed)))
                              first
                              :title)
        mark-next-step   (fn identity-task-by-name [task]
                           (assoc task :is_next_step (= (:title task) next-step)))
        update-triggered (fn [task]
                           (update task :triggered boolean))]
    (for [group checklist]
      (update group :tasks
              (partial map (comp update-triggered mark-next-step))))))

(defn- admin-checklist
  ([] (admin-checklist (state-for-checklist)))
  ([checklist-info]
   (annotate (checklist-items checklist-info))))

(api.macros/defendpoint :get "/admin_checklist"
  "Return various \"admin checklist\" steps and whether they've been completed. You must be a superuser to see this!"
  []
  (validation/check-has-application-permission :setting)
  (admin-checklist))

;; User defaults endpoint

(api.macros/defendpoint :get "/user_defaults"
  "Returns object containing default user details for initial setup, if configured,
   and if the provided token value matches the token in the configuration value."
  [_route-params
   {:keys [token]}]
  (let [{config-token :token :as defaults} (config/mb-user-defaults)]
    (api/check-404 config-token)
    (api/check-403 (= token config-token))
    (dissoc defaults :token)))
