package com.example.demo;

import com.example.demo.utils.WordBreak;
import com.example.demo.utils.WordCount;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;
import java.sql.SQLOutput;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@SpringBootApplication
@RequiredArgsConstructor
public class DemoApplication {

    public static void main(String[] args) throws Exception{
        /*------------------------
        Question 1: Count word
         ------------------------*/
        System.out.println("Question 1: Count word");
        List<Path> files = List.of(
                Path.of("/Users/nguyentienson/Desktop/test/file1.txt"),
                Path.of("/Users/nguyentienson/Desktop/test/file2.txt"),
                Path.of("/Users/nguyentienson/Desktop/test/file3.txt")
        );

        Map<String, Long> result = WordCount.countWords(files);

        result.forEach((word, count) -> System.out.println(word + " : " + count));


        /*------------------------
        Question 2: Break word
         ------------------------*/
        System.out.println("Question 2: Break word");
        // Expected: true  ("leet" + "code")
        System.out.println(WordBreak.wordBreak("leetcode",
                Arrays.asList("leet", "code")));

        // Expected: true  ("apple" + "pen" + "apple")
        System.out.println(WordBreak.wordBreak("applepenapple",
                Arrays.asList("apple", "pen")));

        // Expected: false (không ghép được "sandog")
        System.out.println(WordBreak.wordBreak("catsandog",
                Arrays.asList("cats", "dog", "sand", "and", "cat")));
    }

}
