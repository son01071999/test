package com.example.demo.utils;


import com.example.demo.record.Chunk;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

public class WordCount {

    // If file size <= 64MB -> Read full data of this file in 1 task
    // If file size > 64 -> Separate to chunk
    private static final long SMALLEST_FILE_THRESHOLD = 64L * 1024 * 1024; //64MB

    // Chunk size for big file
    private static final long CHUNK_SIZE = 64L * 1024 * 1024; //64MB

    private static final int STREAM_BUFFER_SIZE = 1024 * 1024; //1MB
    private static final int SEEK_BUFFER_SIZE = 64 * 1024; //64KB

    public static Map<String, Long> countWords(List<Path> files) throws Exception {
        int cpuCore = Runtime.getRuntime().availableProcessors();

        ExecutorService executorService = Executors.newFixedThreadPool(cpuCore);

        // CompletionService will submit multitask
        // And get result of complete task
        CompletionService<Map<String, Long>> completionService = new ExecutorCompletionService<>(executorService);

        /*
        Global counter
        Key: Word
        Value: quantity of existed
        LongAdder better than AtomicLong when multi thread update value
         */
        ConcurrentHashMap<String, LongAdder> globalCounter = new ConcurrentHashMap<>();

        int taskCount = 0;

        try {
            for (Path file : files) {
                long fileSize = Files.size(file);

                if (fileSize == 0) {
                    continue;
                }

                if (fileSize <= SMALLEST_FILE_THRESHOLD) {
                    //Small file: 1 task / 1 file
                    completionService.submit(() ->  countWholeFile(file));
                    taskCount++;
                } else {
                    //Large file: separate chunks
                    for (long start = 0; start < fileSize; start += CHUNK_SIZE){
                        long end = Math.min(fileSize, start + CHUNK_SIZE);

                        Chunk chunk = new Chunk(file, start, end);
                        completionService.submit(() -> countChunk(chunk));
                        taskCount++;
                    }
                }
            }

            /*
            Get result for submitted task
            Each task returns local HashMap
            Merge them into global HashMap
             */
            for (int i = 0; i < taskCount; i++) {
                Map<String, Long> localCounter = completionService.take().get();
                mergeToGlobal(globalCounter, localCounter);
            }
        } finally {
            executorService.shutdown();
        }

        /*
          Convert from ConcurrentHashMap<String, LongAdder> to Map<String, Long>.
          Use TreeMap to get output with alphabet order.
         */
        Map<String, Long> result = new TreeMap<>();
        globalCounter.forEach((word, adder) -> result.put(word, adder.sum()));

        return result;
    }

    // Merge local counter into global counter
    private static void mergeToGlobal(ConcurrentHashMap<String, LongAdder> globalCounter, Map<String, Long> localCounter) {
        localCounter.forEach((word, count) -> globalCounter.computeIfAbsent(word, ignored -> new LongAdder()).add(count));
    }

    // Case small file, read file by BufferInputStream
    private static Map<String, Long> countWholeFile(Path file) throws IOException {
        Map<String, Long> counter = new HashMap<>();
        try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(file), STREAM_BUFFER_SIZE)) {
            byte[] buffer = new byte[STREAM_BUFFER_SIZE];

            StringBuilder currentWord = new StringBuilder(32);

            int read;
            while ((read = inputStream.read(buffer)) != -1){
                for (int i = 0; i < read; i++){
                    int b = buffer[i] & 0xff;

                    if (isSeparator(b)){
                        flushWord(counter, currentWord);
                    } else {
                        // If Java = java = jaVa
                        currentWord.append(toLowerAscii((char) b));

                        // If Java != java, use it
                        // currentWord.append((char) b);
                    }
                }
            }

            //flush final word if this file not end with separator
            flushWord(counter, currentWord);
        }
        return counter;
    }

    private static Map<String, Long> countChunk(Chunk chunk) throws IOException {
        Path file = chunk.file();
        long start = chunk.start();
        long end = chunk.end();

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            long actualStart = start;
            long actualEnd = end;

            /*
            1. Handle start boundary
            Example: Hello Java Spring
            And Chunk separate: Hello Ja | va Spring
                                chunk-1     chunk-2
            That is wrong if chunk-2 counts 'va'

            Check: if before-byte of actualStart and after-byte of actualStart are not separator
            -> actualStart is inside word
             */
            if (actualStart > 0 && actualStart < fileSize) {
                int previousByte = readByte(channel, actualStart - 1);
                int currentByte = readByte(channel, actualStart);

                boolean startIsInsideWord = !isSeparator(previousByte) && !isSeparator(currentByte);
                if (startIsInsideWord){
                  long nextSeparator = seekNextSeparator(channel, actualStart, fileSize);
                  if (nextSeparator >= fileSize){
                      /* example: we separate n chunk
                      chunk n-1: .... Hello Ja
                      chunk n : va
                      => ignore 'va' of last chunk because 'Java' is counted by the previous chunk
                       */

                      return new HashMap<>();
                  }

                  actualStart = nextSeparator + 1;
                }
            }

            /*
            2. Handle end boundary
            Example: Hello Java Spring
            And Chunk separate: Hello Ja | va Spring
                                chunk-1     chunk-2
            If chunk-1 counts 'Ja' only, that's wrong.
            Chunk-1 need extend actualEnd to the next separator to count 'Java'

            Rule: If actualEnd is inside word, extend actualEnd to the next separator
             */
            if (actualEnd < fileSize && actualEnd > actualStart) {
                int byteBeforeEnd = readByte(channel, actualEnd - 1);
                int byteAtEnd = readByte(channel, actualEnd);

                boolean endIsInsideWord = !isSeparator(byteBeforeEnd) && !isSeparator(byteAtEnd);
                if (endIsInsideWord) {
                   actualEnd = seekNextSeparator(channel, actualEnd, fileSize);
                }
            }

            if (actualStart >= actualEnd) {
                return new HashMap<>();
            }

            long length = actualEnd - actualStart;

            // Using memory-mapped file for chunk
            // OS will map this file's area into memory
            MappedByteBuffer mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, actualStart, length);

            return countByteBuffer(mappedBuffer);
        }

    }

    /*
    Count word from ByteBuffer
    No use 'split()' because 'split()' will create many String objects
     */
    private static Map<String, Long> countByteBuffer(MappedByteBuffer buffer) {
        Map<String, Long> counter = new HashMap<>();
        StringBuilder currentWord = new StringBuilder(32);

        while ((buffer.hasRemaining())) {
            int b = buffer.get() & 0xff;

            if (isSeparator(b)) {
               flushWord(counter, currentWord);
            } else {
                currentWord.append(toLowerAscii((char) b));
            }
        }
        flushWord(counter, currentWord);

        return counter;
    }

    /*
    Find the next separator from position 'from'
    Return
        - position if next separator is found
        - max if next separator is not found
     */
    private static long seekNextSeparator(FileChannel channel, long from, long max) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(SEEK_BUFFER_SIZE);

        long position = from;

        while (position < max){
            buffer.clear();
            int size = (int) Math.min(buffer.capacity(), max - position);
            buffer.limit(size);

            int read = channel.read(buffer, position);
            if (read <= 0){
                break;
            }

            buffer.flip();

            for (int i = 0; i < read; i++) {
                int b = buffer.get(i) & 0xff;
                if (isSeparator(b)) {
                    return position + i;
                }
            }
            position += read;
        }

        return max;
    }

    /*
    Input is English without punctuation
    Then separator can be : space / new line / tab / carriage return
    All of them has ASCII <= 32
     */
    private static boolean isSeparator(int b){
        return b <= 32;
    }

    // Add current word into local Map if it is not empty
    private static void flushWord(Map<String, Long> counter, StringBuilder currentWord){
        if (currentWord.isEmpty()) {
           return;
        }

        String word = currentWord.toString();
        counter.merge(word, 1L, Long::sum);
        currentWord.setLength(0);
    }

    // Use this method if Java = java = jaVa
    // If Java != java, skip using this method
    private static char toLowerAscii(char c){
        if (c >= 'A' && c <= 'Z'){
            return (char) (c + 32);
        }
        return c;
    }

    // Read 1 byte with position in file
    private static int readByte(FileChannel channel, long position) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        int read = channel.read(buffer, position);
        if (read < 0) {
            return -1;
        }
        buffer.flip();
        return buffer.get() & 0xff;
    }
}
