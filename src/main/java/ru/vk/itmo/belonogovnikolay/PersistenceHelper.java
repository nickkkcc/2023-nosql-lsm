package ru.vk.itmo.belonogovnikolay;

import ru.vk.itmo.BaseEntry;
import ru.vk.itmo.Entry;
import ru.vk.itmo.belonogovnikolay.exceptions.PathNotFoundException;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final Path[] pathsToFile;
    private final long[] fileSizes;
    private final Path basePath;

    private PersistenceHelper(Path basePath) {
        this.pathsToFile = new Path[2];
        this.fileSizes = new long[2];
        this.basePath = basePath;
        resolvePaths();
    }

    /**
     * Returns instance of PersistenceHelper class.
     *
     * @param basePath directory for storing snapshots.
     * @return PersistentHelper instance.
     * @throws PathNotFoundException is thrown when the path to the directory with snapshot files is not specified.
     */
    public static PersistenceHelper newInstance(Path basePath) throws PathNotFoundException {
        if (basePath == null) {
            throw new PathNotFoundException("The directory to the "
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
    public void writeEntries(NavigableMap<MemorySegment,
            Entry<MemorySegment>> entries) throws IOException, PathNotFoundException {

        int size = entries.size();

        if (size == 0) {
            return;
        }
        positionOffsets = new long[size * 2 + 1];

        long fileSize = entries.values().stream()
                .mapToLong(entry -> entry.key().byteSize() + entry.value().byteSize()).sum();

        if (Files.notExists(pathsToFile[0]) || Files.notExists(pathsToFile[1])) {
            Files.deleteIfExists(pathsToFile[0]);
            Files.createFile(pathsToFile[0]);

            Files.deleteIfExists(pathsToFile[1]);
            Files.createFile(pathsToFile[1]);
        }

        this.dataMappedSegment = map(pathsToFile[0], fileSize,
                FileChannel.MapMode.READ_WRITE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        this.offsetMappedSegment = map(pathsToFile[1],
                (long) Long.BYTES * positionOffsets.length,
                FileChannel.MapMode.READ_WRITE, StandardOpenOption.WRITE, StandardOpenOption.READ);

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

        if (Files.notExists(pathsToFile[0]) || Files.notExists(pathsToFile[1])) {
            return null;
        }

        readingPreparation();
        MemorySegmentComparator segmentComparator = new MemorySegmentComparator();

        long index = 0;
        long beginLong;
        long endLong;
        long keyValueSize;

        while (index < (this.fileSizes[1] - Long.BYTES) / 8 - 1) {
            beginLong = offsetMappedSegment.getAtIndex(ValueLayout.JAVA_LONG, index);
            endLong = offsetMappedSegment.getAtIndex(ValueLayout.JAVA_LONG, index + 1);
            keyValueSize = endLong - beginLong;
            MemorySegment keySegment = dataMappedSegment.asSlice(beginLong, keyValueSize);
            if (segmentComparator.compare(keySegment, key) == 0) {
                keyValueSize = offsetMappedSegment.getAtIndex(ValueLayout.JAVA_LONG, index + 2) - endLong;
                return new BaseEntry<>(keySegment, dataMappedSegment.asSlice(endLong, keyValueSize));
            }
            index++;
        }
        return null;
    }

    /**
     * Function of mapping MemorySegment and file.
     *
     * @param filePath       file path.
     * @param byteSize       file size (offset).
     * @param mode           {@link FileChannel.MapMode} mode.
     * @param channelOptions {@link OpenOption} option.
     * @return {@link MemorySegment} which map with file.
     * @throws IOException is thrown when exceptions occur while working with a file.
     */
    private MemorySegment map(Path filePath, long byteSize, FileChannel.MapMode mode,
                              OpenOption... channelOptions) throws IOException {
        try (FileChannel channel = FileChannel.open(filePath, channelOptions)) {
            return channel.map(mode, 0, byteSize, Arena.ofConfined());
        }
    }

    /**
     * Function resolves {@link #basePath} to file {@link #pathsToFile paths}.
     */
    private void resolvePaths() {
        this.pathsToFile[0] = this.basePath.resolve(FileType.DATA.toString());
        this.pathsToFile[1] = this.basePath.resolve(FileType.OFFSET.toString());
    }

    /**
     * Until function to prepare for reading operations.
     *
     * @throws IOException is thrown when exceptions occur while working with a file.
     */
    private void readingPreparation() throws IOException {
        this.fileSizes[1] = Files.size(pathsToFile[1]);
        this.fileSizes[0] = Files.size(pathsToFile[0]);

        this.dataMappedSegment = map(pathsToFile[0], this.fileSizes[0],
                FileChannel.MapMode.READ_ONLY, StandardOpenOption.READ);
        this.offsetMappedSegment = map(pathsToFile[1], this.fileSizes[1],
                FileChannel.MapMode.READ_ONLY, StandardOpenOption.READ);
    }
}