import cx from "classnames";
import { Fragment } from "react";
import { t } from "ttag";

import { useDocsUrl } from "metabase/common/hooks";
import ExternalLink from "metabase/core/components/ExternalLink";
import CS from "metabase/css/core/index.css";
import { Box, Icon, Text } from "metabase/ui";
import { getHelpDocsUrl } from "metabase-lib/v1/expressions/helper-text-strings";
import type { HelpText } from "metabase-lib/v1/expressions/types";

import ExpressionEditorHelpTextS from "./ExpressionEditorHelpText.module.css";

export type ExpressionEditorHelpTextProps = {
  helpText: HelpText | null | undefined;
};

export const ExpressionEditorHelpText = ({
  helpText,
}: ExpressionEditorHelpTextProps) => {
  const { url: docsUrl, showMetabaseLinks } = useDocsUrl(
    helpText ? getHelpDocsUrl(helpText) : "",
  );

  if (!helpText) {
    return null;
  }

  const { description, structure, args } = helpText;

  return (
    <>
      {/* Prevent stealing focus from input box causing the help text to be closed (metabase#17548) */}
      <Box
        className={ExpressionEditorHelpTextS.Container}
        onMouseDown={evt => evt.preventDefault()}
        data-testid="expression-helper-popover"
      >
        <Box
          className={ExpressionEditorHelpTextS.FunctionHelpCode}
          data-testid="expression-helper-popover-structure"
        >
          {structure}
          {args != null && (
            <>
              (
              {args.map(({ name }, index) => (
                <span key={name}>
                  <Text
                    component="span"
                    className={
                      ExpressionEditorHelpTextS.FunctionHelpCodeArgument
                    }
                  >
                    {name}
                  </Text>
                  {index + 1 < args.length && ", "}
                </span>
              ))}
              )
            </>
          )}
        </Box>
        <Box className={ExpressionEditorHelpTextS.Divider} />

        <Text>{description}</Text>

        {args != null && (
          <Box
            className={ExpressionEditorHelpTextS.ArgumentsGrid}
            data-testid="expression-helper-popover-arguments"
          >
            {args.map(({ name, description: argDescription }) => (
              <Fragment key={name}>
                <Box
                  className={cx(
                    ExpressionEditorHelpTextS.ArgumentTitle,
                    CS.textMonospace,
                  )}
                >
                  {name}
                </Box>
                <Text lh="normal">{argDescription}</Text>
              </Fragment>
            ))}
          </Box>
        )}

        <Box
          className={ExpressionEditorHelpTextS.BlockSubtitleText}
          data-testid="argument-example"
        >{t`Example`}</Box>
        <Box
          className={cx(
            ExpressionEditorHelpTextS.ExampleCode,
            CS.textMonospace,
          )}
        >
          {helpText.example}
        </Box>
        {showMetabaseLinks && (
          <ExternalLink
            className={ExpressionEditorHelpTextS.DocumentationLink}
            href={docsUrl}
            target="_blank"
          >
            <Icon m="0.25rem 0.5rem" name="reference" size={12} />
            {t`Learn more`}
          </ExternalLink>
        )}
      </Box>
    </>
  );
};
