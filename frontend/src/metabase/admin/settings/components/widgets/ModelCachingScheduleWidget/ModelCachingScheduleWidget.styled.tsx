// eslint-disable-next-line no-restricted-imports
import { css } from "@emotion/react";
// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";

import { SettingSelect } from "../SettingSelect";

export const Root = styled.div`
  display: flex;
  flex-direction: column;
`;

export const WidgetsRow = styled.div`
  display: flex;
  flex-direction: row;
  gap: 1.3rem;
`;

export const WidgetContainer = styled.div`
  display: flex;
  flex-direction: column;
  min-height: 75.5px;
`;

export const StyledSettingSelect = styled(SettingSelect)`
  width: 125px;
  margin-top: 12px;
`;

export const commonLabelStyle = css`
  display: block;
  color: var(--mb-color-text-medium);
`;

export const SelectLabel = styled.span`
  ${commonLabelStyle}
  font-size: 0.75rem;
  font-weight: 700;
  line-height: 0.875rem;
  margin-top: 4px;
`;

export const Description = styled.span`
  margin-top: 1.5rem;
  color: var(--mb-color-text-medium);
`;
