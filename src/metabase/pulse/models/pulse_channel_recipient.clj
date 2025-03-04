(ns metabase.pulse.models.pulse-channel-recipient
  (:require
   [methodical.core :as methodical]
   [toucan2.core :as t2]))

(methodical/defmethod t2/table-name :model/PulseChannelRecipient [_model] :pulse_channel_recipient)

(derive :model/PulseChannelRecipient :metabase/model)

;;; Deletes `PulseChannel` if the recipient being deleted is its last recipient. (This only applies
;;; to PulseChannels with User subscriptions; Slack PulseChannels and ones with email address subscriptions are not
;;; automatically deleted.
(t2/define-before-delete :model/PulseChannelRecipient
  [{channel-id :pulse_channel_id, pulse-channel-recipient-id :id}]
  (let [other-recipients-count (t2/count :model/PulseChannelRecipient
                                         :pulse_channel_id channel-id
                                         :id               [:not= pulse-channel-recipient-id])
        last-recipient?        (zero? other-recipients-count)]
    (when last-recipient?
      ;; make sure this channel doesn't have any email-address (non-User) recipients.
      (let [details              (t2/select-one-fn :details :model/PulseChannel :id channel-id)
            has-email-addresses? (seq (:emails details))]
        (when-not has-email-addresses?
          (t2/delete! :model/PulseChannel :id channel-id))))))
