package com.example.demo.utils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WordBreak {

    public static boolean wordBreak(String s, List<String> wordDict) {

        // Step 1: Add dict into HashSet to lookup O(1) insteadOf O(m)
        Set<String> wordSet = new HashSet<>(wordDict);

        // Get the length of longest word in dict
        // limit window j, prevent check substrings which can not be valid word
        int maxWordLen = wordDict.stream()
                .mapToInt(String::length)
                .max()
                .orElse(0);

        int n = s.length();

        // Step 2: Create DP array
        // dp[i] = true  ->  s[0..i-1] can be separate to valid words
        // dp[i] = false ->  can not
        boolean[] dp = new boolean[n + 1];

        // Base case: empty String (length = 0)
        dp[0] = true;

        // Step 3: Fill data into DP array from left to right
        for (int i = 1; i <= n; i++) {

            // Only look up max maxWordLen characters
            // → if j < i - maxWordLen then s[j..i-1] is longer than the longest word -> skip check
            int start = Math.max(0, i - maxWordLen);

            for (int j = start; j < i; j++) {

                // Condition: s[0..j-1] can split (dp[j] == true)
                //            AND s[j..i-1] is a word in dict
                if (dp[j] && wordSet.contains(s.substring(j, i))) {

                    dp[i] = true;
                    break; // Found the way to valid split to position i -> break early
                }
            }
        }

        // Step 4: Full String can split or not
        return dp[n];
    }
}
