package io.github.eoinkanro.filestoimages.transformer;

import io.github.eoinkanro.filestoimages.conf.ConfigException;
import io.github.eoinkanro.filestoimages.conf.InputCLIArgument;
import io.github.eoinkanro.filestoimages.conf.InputCLIArgumentsHolder;
import io.github.eoinkanro.filestoimages.utils.BytesUtils;
import io.github.eoinkanro.filestoimages.utils.FileUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;

@RequiredArgsConstructor
public abstract class Transformer {

    protected static final String COMMON_EXCEPTION_DESCRIPTION = "Something went wrong";

    private final InputCLIArgument<Boolean> activeTransformerArgument;
    protected final InputCLIArgument<String> pathToFileArgument;

    @Autowired
    protected InputCLIArgumentsHolder inputCLIArgumentsHolder;
    @Autowired
    protected FileUtils fileUtils;
    @Autowired
    protected BytesUtils bytesUtils;

    public final void transform() {
        if (Boolean.FALSE.equals(inputCLIArgumentsHolder.getArgument(activeTransformerArgument))) {
            return;
        }
        checkConfiguration();
        process();
    }

    protected abstract void process();

    protected void checkConfiguration() {
        if (StringUtils.isBlank(inputCLIArgumentsHolder.getArgument(pathToFileArgument))) {
            throw new ConfigException("Target path for transforming files to images is empty");
        }

        String input = fileUtils.getAbsolutePath(inputCLIArgumentsHolder.getArgument(pathToFileArgument));
        if (!new File(input).exists()) {
            throw new ConfigException("Target path \"" + input + "\" doesn't exist");
        }
    }

}
