package ru.vk.itmo.belonogovnikolay;

import ru.vk.itmo.BaseEntry;
import ru.vk.itmo.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Util class for write, read, and persistence recovery operations when the {@link InMemoryTreeDao DAO} is restarted.
 *
 * @author Belonogov Nikolay
 */
public final class PersistenceHelper {

    private MemorySegment dataMappedSegment;
    private MemorySegment offsetMappedSegment;
    private long[] positionOffsets;
    private int position;
    private Path pathToDataFile;
    private Path pathToOffsetFile;
    private long dataFileSize;
    private long offsetFileSize;
    private final Path basePath;
    private final MemorySegmentComparator segmentComparator;
    private boolean isReadingPrepared = false;

    private PersistenceHelper(Path basePath) {
        this.basePath = basePath;
        this.segmentComparator = new MemorySegmentComparator();
        resolvePaths();
    }

    /**
     * Returns instance of PersistenceHelper class.
     *
     * @param basePath directory for storing snapshots.
     * @return PersistentHelper instance.
     * @throws NullPointerException is thrown when the path to the directory with snapshot files is not specified.
     */
    public static PersistenceHelper newInstance(Path basePath) throws NullPointerException {
        if (basePath == null) {
            throw new NullPointerException("The directory to the "
                    + " files was not specified in the config file.");
        }
        return new PersistenceHelper(basePath);
    }

    /**
     * The function writes to the file specified in the config. If the config is not specified, an exception is thrown.
     *
     * @param entries data to be written to disk.
     * @throws IOException is thrown when exceptions occur while working with a file.
     */
    public void writeEntries(NavigableMap<MemorySegment, Entry<MemorySegment>> entries) throws IOException {

        int size = entries.size();

        if (size == 0) {
            return;
        }
        positionOffsets = new long[size * 2 + 1];

        long fileSize = 0;
        for (Map.Entry<MemorySegment, Entry<MemorySegment>> entry : entries.entrySet()) {
            fileSize += entry.getKey().byteSize() + entry.getValue().value().byteSize();
        }

        if (Files.notExists(pathToDataFile) || Files.notExists(pathToOffsetFile)) {
            Files.deleteIfExists(pathToDataFile);
            Files.createFile(pathToDataFile);

            Files.deleteIfExists(pathToOffsetFile);
            Files.createFile(pathToOffsetFile);
        }

        isReadingPrepared = false;
        this.dataMappedSegment = mapFileWriteRead(pathToDataFile, fileSize);
        this.offsetMappedSegment = mapFileWriteRead(pathToOffsetFile,
                (long) Long.BYTES * positionOffsets.length);

        entries.values().forEach(entry -> {
            long keySize = entry.key().byteSize();
            long valueSize = entry.value().byteSize();
            positionOffsets[position + 1] = positionOffsets[position] + keySize;
            positionOffsets[position + 2] = positionOffsets[position + 1] + valueSize;
            this.dataMappedSegment.asSlice(positionOffsets[position], keySize).copyFrom(entry.key());
            this.dataMappedSegment.asSlice(positionOffsets[position + 1], valueSize).copyFrom(entry.value());
            position = position + 2;
        });

        offsetMappedSegment
                .asSlice(0L, (long) Long.BYTES * positionOffsets.length)
                .copyFrom(MemorySegment.ofArray(positionOffsets));
    }

    /**
     * Returns entry of data which is read from file.
     *
     * @param key is search key of data entry which is read from file.
     * @return entry of data.
     * @throws IOException is thrown when exceptions occur while working with a file.
     */
    public Entry<MemorySegment> readEntry(MemorySegment key) throws IOException {

        if (Files.notExists(pathToDataFile) || Files.notExists(pathToOffsetFile)) {
            return null;
        }

        if (!isReadingPrepared) {
            readingPreparation();
            isReadingPrepared = true;
        }

        long index = 0;
        long beginLong;
        long endLong;
        long keyValueSize;
        long offsetFileOffsetCount = (this.offsetFileSize - Long.BYTES) / 8 - 1;

        while (index < offsetFileOffsetCount) {
            beginLong = offsetMappedSegment.getAtIndex(ValueLayout.JAVA_LONG, index);
            endLong = offsetMappedSegment.getAtIndex(ValueLayout.JAVA_LONG, index + 1);
            keyValueSize = endLong - beginLong;
            MemorySegment keySegment = dataMappedSegment.asSlice(beginLong, keyValueSize);
            if (this.segmentComparator.compare(keySegment, key) == 0) {
                keyValueSize = offsetMappedSegment.getAtIndex(ValueLayout.JAVA_LONG, index + 2) - endLong;
                return new BaseEntry<>(keySegment, dataMappedSegment.asSlice(endLong, keyValueSize));
            }
            index++;
        }
        return null;
    }

    /**
     * Function of mapping MemorySegment and file in READ-ONLY mode.
     *
     * @param filePath file path.
     * @param byteSize file size (offset).
     * @return {@link MemorySegment} which map with file.
     * @throws IOException is thrown when exceptions occur while working with a file.
     */
    private MemorySegment mapFileReadOnly(Path filePath, long byteSize) throws IOException {
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, byteSize, Arena.ofConfined());
        }
    }

    /**
     * Function of mapping MemorySegment and file in READ-WRITE mode.
     *
     * @param filePath file path.
     * @param byteSize file size (offset).
     * @return {@link MemorySegment} which map with file.
     * @throws IOException is thrown when exceptions occur while working with a file.
     */
    private MemorySegment mapFileWriteRead(Path filePath, long byteSize) throws IOException {
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            return channel.map(FileChannel.MapMode.READ_WRITE, 0, byteSize, Arena.ofConfined());
        }
    }

    /**
     * Function resolves {@link #basePath} to file {@link #pathToDataFile}, {@link #pathToOffsetFile} paths.
     */
    private void resolvePaths() {
        this.pathToDataFile = this.basePath.resolve(FileType.DATA.toString());
        this.pathToOffsetFile = this.basePath.resolve(FileType.OFFSET.toString());
    }

    /**
     * Until function to prepare for reading operations.
     *
     * @throws IOException is thrown when exceptions occur while working with a file.
     */
    private void readingPreparation() throws IOException {
        this.offsetFileSize = Files.size(pathToOffsetFile);
        this.dataFileSize = Files.size(pathToDataFile);

        this.dataMappedSegment = mapFileReadOnly(pathToDataFile, this.dataFileSize);
        this.offsetMappedSegment = mapFileReadOnly(pathToOffsetFile, this.offsetFileSize);
    }
}
