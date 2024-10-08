import type { MouseEvent, Ref } from "react";
import { forwardRef } from "react";
import { t } from "ttag";

import { ToolbarButton } from "metabase/components/ToolbarButton";
import { useSelector } from "metabase/lib/redux";
import { getSetting } from "metabase/selectors/settings";

export type ResourceEmbedButtonProps = {
  onClick?: () => void;
  disabled?: boolean;
  hasBackground?: boolean;
  tooltip?: string | null;
};

export const ResourceEmbedButton = forwardRef(function ResourceEmbedButton(
  {
    onClick,
    disabled = false,
    hasBackground = true,
    tooltip = null,
  }: ResourceEmbedButtonProps,
  ref: Ref<HTMLButtonElement>,
) {
  const isPublicSharingEnabled = useSelector(state =>
    getSetting(state, "enable-public-sharing"),
  );

  const tooltipLabel =
    tooltip ?? (isPublicSharingEnabled ? t`Sharing` : t`Embedding`);

  const onHeaderButtonClick = (event: MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    onClick?.();
  };

  return (
    <ToolbarButton
      data-disabled={disabled || undefined}
      data-testid="resource-embed-button"
      icon="share"
      disabled={disabled}
      onClick={onHeaderButtonClick}
      ref={ref}
      hasBackground={hasBackground}
      aria-label={tooltipLabel}
      tooltipLabel={tooltipLabel}
    />
  );
});
