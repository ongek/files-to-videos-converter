package io.github.eoinkanro.filestovideosconverter.utils;

import io.github.eoinkanro.filestovideosconverter.conf.InputCLIArgumentsHolder;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;

@Log4j2
@Component
public class FileUtils {

    public static final String INDEX_SIZE_SEPARATOR_SUPPORT = "-i";
    public static final String DUPLICATE_FACTOR_SEPARATOR = "-d";
    public static final String LAST_ZERO_BYTES_COUNT_SEPARATOR = "-z";

    private static final int ZERO_SCAN_BUFFER_SIZE = 64 * 1024; // 64KB ブロック逆走査バッファ
    private static final String CURRENT_PATH_CACHE = Path.of("").toAbsolutePath().toString();

    @Autowired
    private InputCLIArgumentsHolder inputCLIArgumentsHolder;
    @Autowired
    private CommonUtils commonUtils;

    //--------------- Result files -------------------

    public File getFilesToVideosResultFile(File original, int lastZeroBytesCount) throws IOException {
        String originalAbsolutePath = original.getAbsolutePath();
        StringBuilder resultBuilder = new StringBuilder(originalAbsolutePath.length() + 64);

        resultBuilder.append(getResultPathForVideos()).append(File.separatorChar);

        if (!originalAbsolutePath.contains(getCurrentPath())) {
            int firstSep = originalAbsolutePath.indexOf(File.separatorChar);
            resultBuilder.append(firstSep >= 0 ? originalAbsolutePath.substring(firstSep + 1) : originalAbsolutePath);
        } else {
            String basePath = getAbsolutePath(inputCLIArgumentsHolder.getArgument(FILES_PATH));
            String pathWithoutBeginning = originalAbsolutePath.substring(basePath.length());

            if (pathWithoutBeginning.isBlank()) {
                pathWithoutBeginning = File.separator + original.getName();
            } else if (!pathWithoutBeginning.startsWith(File.separator)) {
                pathWithoutBeginning = File.separator + pathWithoutBeginning;
            }

            resultBuilder.append(pathWithoutBeginning);
        }

        resultBuilder.append(DUPLICATE_FACTOR_SEPARATOR)
                     .append(inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR))
                     .append(LAST_ZERO_BYTES_COUNT_SEPARATOR)
                     .append(lastZeroBytesCount)
                     .append(".mp4");

        File result = new File(resultBuilder.toString());
        createFile(result);
        return result;
    }

    public File getVideosToFilesResultFile(String originalPath) throws IOException {
        File result = new File(getResultPathForFiles() + originalPath);
        createFile(result);
        return result;
    }

    //---------------- Result Folder Paths --------------------

    public String getResultPathForFiles() {
        return getResultPath(inputCLIArgumentsHolder.getArgument(FILES_PATH));
    }

    public String getResultPathForVideos() {
        return getResultPath(inputCLIArgumentsHolder.getArgument(VIDEOS_PATH));
    }

    private String getResultPath(String resultFolderName) {
        if (resultFolderName.contains(File.separator)) {
            resultFolderName = resultFolderName.substring(resultFolderName.indexOf(File.separator) + 1);
        }
        return getCurrentPath() + File.separator + resultFolderName;
    }

    public String getCurrentPath() {
        return CURRENT_PATH_CACHE;
    }

    public String getAbsolutePath(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            return getCurrentPath() + File.separator + path;
        }
        return path;
    }

    //------------------- Metadata (超高速化) -------------------

    /**
     * 【超高速化】ファイル末尾の連続ゼロバイト数を 64KB ブロック逆走査で瞬時にカウント
     * (数万回のシステムコールを 1 回のブロック読み出しに集約)
     */
    public int calculateLastZeroBytesAmount(File file) {
        long fileLength = file.length();
        if (fileLength == 0) {
            return 0;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {

            ByteBuffer buffer = ByteBuffer.allocateDirect(ZERO_SCAN_BUFFER_SIZE);
            long currentPos = fileLength;
            int totalZeroCount = 0;

            while (currentPos > 0) {
                int bytesToRead = (int) Math.min(currentPos, ZERO_SCAN_BUFFER_SIZE);
                long readStartPos = currentPos - bytesToRead;

                buffer.clear();
                buffer.limit(bytesToRead);
                channel.read(buffer, readStartPos);
                buffer.flip();

                // バッファの末尾から先頭に向かって逆走査
                int index = bytesToRead - 1;
                while (index >= 0 && buffer.get(index) == 0) {
                    totalZeroCount++;
                    index--;
                }

                // 0以外のバイトが見つかったら即終了
                if (index >= 0) {
                    break;
                }

                currentPos = readStartPos;
            }

            return totalZeroCount;
        } catch (Exception e) {
            throw new FileException("Error during reading last bytes of file " + file, e);
        }
    }

    public int getImageDuplicateFactor(String filePath) {
        return commonUtils.parseInt(getStringMetadata(filePath, DUPLICATE_FACTOR_SEPARATOR, LAST_ZERO_BYTES_COUNT_SEPARATOR));
    }

    public int getImageLastZeroBytesCount(String filePath) {
        return commonUtils.parseInt(getStringMetadata(filePath, LAST_ZERO_BYTES_COUNT_SEPARATOR, "."));
    }

    private String getStringMetadata(String filePath, String tag, String lastSymbol) {
        int tagIdx = filePath.lastIndexOf(tag);
        if (tagIdx >= 0) {
            int start = tagIdx + tag.length();
            int end = filePath.indexOf(lastSymbol, start);
            if (end > start) {
                return filePath.substring(start, end);
            }
        }
        return "";
    }

    public String getOriginalNameOfFile(File file, String startPath) {
        String basePath = getAbsolutePath(startPath);
        String filePath = file.getAbsolutePath();
        String result = filePath.startsWith(basePath) ? filePath.substring(basePath.length()) : filePath;

        if (result.isBlank()) {
            result = file.getName();
        }

        int idxSupport = result.lastIndexOf(INDEX_SIZE_SEPARATOR_SUPPORT);
        if (idxSupport >= 0) {
            result = result.substring(0, idxSupport);
        } else {
            int idxDf = result.lastIndexOf(DUPLICATE_FACTOR_SEPARATOR);
            if (idxDf >= 0) {
                result = result.substring(0, idxDf);
            }
        }

        if (!result.startsWith(File.separator)) {
            result = File.separator + result;
        }

        return result;
    }

    //----------------- Create file ---------------------

    private void createFile(File file) throws IOException {
        if (!file.exists()) {
            Path parent = file.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.createFile(file.toPath());
        }
    }
}
