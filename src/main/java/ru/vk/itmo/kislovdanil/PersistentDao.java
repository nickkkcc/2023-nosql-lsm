package ru.vk.itmo.kislovdanil;

import ru.vk.itmo.Config;
import ru.vk.itmo.Dao;
import ru.vk.itmo.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class PersistentDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private final ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> storage =
            new ConcurrentSkipListMap<>(new MemSegComparator());
    private final SSTable table;
    private final Config config;
    private final Comparator<MemorySegment> comp = new MemSegComparator();

    public PersistentDao(Config config) throws IOException {
        this.config = config;
        // SSTable constructor with rewrite=false reads table data from disk if it exists
        this.table = new SSTable(config.basePath(), comp, 0L, storage, false);
    }

    private static class MemSegComparator implements Comparator<MemorySegment> {
        @Override
        public int compare(MemorySegment o1, MemorySegment o2) {
            long mismatch = o1.mismatch(o2);
            if (mismatch == -1) {
                return 0;
            }
            if (mismatch == Math.min(o1.byteSize(), o2.byteSize())) {
                return Long.compare(o1.byteSize(), o2.byteSize());
            }
            return Byte.compare(o1.get(ValueLayout.JAVA_BYTE, mismatch), o2.get(ValueLayout.JAVA_BYTE, mismatch));
        }
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        if (from == null && to == null) return storage.values().iterator();
        if (from != null && to == null) return storage.tailMap(from).values().iterator();
        if (from == null) return storage.headMap(to).values().iterator();
        return storage.subMap(from, to).values().iterator();
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        Entry<MemorySegment> ans = storage.get(key);
        if (ans != null) return ans;
        try {
            return table.find(key);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        storage.put(entry.key(), entry);
    }

    @Override
    public void close() throws IOException {
        if (!storage.isEmpty()) {
            // SSTable constructor with rewrite=true writes MemTable data on disk deleting old data if it exists
            new SSTable(config.basePath(), comp, 0L, storage, true);
        }
    }
}
