package io.github.eoinkanro.filestovideosconverter.conf;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.cli.*;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.HELP;

/**
 * It provides data from CLI or default values
 */
@Component
@Log4j2
public class InputCLIArgumentsHolder {

    private CommandLine cmd;

    public boolean init(String... args) throws Exception {
        Options options = new Options();
        for (Field field : InputCLIArguments.class.getDeclaredFields()) {
            if (field.get(null) instanceof InputCLIArgument<?> inputCLIArgument) {
                options.addOption(inputCLIArgument.getShortName(),
                        inputCLIArgument.getFullName(),
                        inputCLIArgument.isHasArg(),
                        inputCLIArgument.getDescription());
            }
        }

        if (args.length == 0) {
            printHelp(options);
            return false;
        }

        CommandLineParser parser = new DefaultParser();
        cmd = parser.parse(options, args);

        if (log.isDebugEnabled()) {
            log.debug(cmd.getArgList());
        }
        if (cmd.hasOption(HELP.getShortName())) {
            printHelp(options);
        }
        return true;
    }

    private void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("Files to Images Transformer", options);
    }

    @SuppressWarnings("unchecked")
    public <T> T getArgument(InputCLIArgument<T> inputCLIArgument) {
        if (inputCLIArgument.getAssignedValue() != null) {
            return inputCLIArgument.getAssignedValue();
        }

        T cliValue = switch (inputCLIArgument.getDefaultValue()) {
            case Boolean b -> (T) Boolean.valueOf(cmd.hasOption(inputCLIArgument.getShortName()));
            case String s  -> (T) cmd.getOptionValue(inputCLIArgument.getShortName());
            case null, default -> (T) castArgumentToInt(inputCLIArgument);
        };

        if (cliValue == null) {
            inputCLIArgument.setAssignedValue(inputCLIArgument.getDefaultValue());
        } else {
            inputCLIArgument.setAssignedValue(cliValue);
        }

        return inputCLIArgument.getAssignedValue();
    }

    private <T> Integer castArgumentToInt(InputCLIArgument<T> inputCLIArgument) {
        String optionVal = cmd.getOptionValue(inputCLIArgument.getShortName());
        if (optionVal == null) {
            return null;
        }
        try {
            return Integer.parseInt(optionVal);
        } catch (NumberFormatException e) {
            if (log.isDebugEnabled()) {
                log.debug("Can't cast {} of {}", optionVal, inputCLIArgument);
            }
            return null;
        }
    }
}
