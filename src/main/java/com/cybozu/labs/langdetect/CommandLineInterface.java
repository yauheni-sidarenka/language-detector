/*
 * Copyright 2011 Nakatani Shuyo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cybozu.labs.langdetect;

import be.frma.langguess.IOUtils;
import be.frma.langguess.LangProfileWriter;
import com.cybozu.labs.langdetect.util.LangProfile;
import com.google.common.base.Optional;
import com.optimaize.langdetect.DetectedLanguage;
import com.optimaize.langdetect.LanguageDetector;
import com.optimaize.langdetect.LanguageDetectorBuilder;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfile;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import com.optimaize.langdetect.text.CommonTextObjectFactories;
import com.optimaize.langdetect.text.TextObject;
import com.optimaize.langdetect.text.TextObjectFactory;

import java.io.*;
import java.util.*;

/**
 * LangDetect Command Line Interface.
 *
 * <p>This is a command line interface of Language Detection Library "LangDetect".</p>
 *
 * <p>Renamed: this class was previously known as "Command".</p>
 *
 * <p>TODO after my recent changes switching to the new Detector this code is untested. -Fabian</p>
 *
 * @author Nakatani Shuyo
 * @author Francois ROLAND
 * @author Fabian Kessler
 */
public class CommandLineInterface {

    /** smoothing default parameter (ELE) */
    private static final double DEFAULT_ALPHA = 0.5;

    /** for Command line easy parser */
    private Map<String, String> opt_with_value = new HashMap<>();
    private Map<String, String> values = new HashMap<>();
    private Set<String> opt_without_value = new HashSet<>();
    private List<String> arglist = new ArrayList<>();

    /**
     * Command Line Interface
     * @param args command line arguments
     */
    public static void main(String[] args) throws IOException {
        CommandLineInterface cli = new CommandLineInterface();
        cli.addOpt("-d", "directory", "./");
        cli.addOpt("-a", "alpha", "" + DEFAULT_ALPHA);
        cli.parse(args);

        if (cli.hasOpt("--genprofile")) {
            cli.generateProfile();
        } else if (cli.hasOpt("--detectlang")) {
            cli.detectLang();
        } else if (cli.hasOpt("--batchtest")) {
            cli.batchTest();
        }
    }

    /**
     * Command line easy parser
     * @param args command line arguments
     */
    private void parse(String[] args) {
        for (int i=0; i<args.length; i++) {
            if (opt_with_value.containsKey(args[i])) {
                String key = opt_with_value.get(args[i]);
                values.put(key, args[i+1]);
                i++;
            } else if (args[i].startsWith("-")) {
                opt_without_value.add(args[i]);
            } else {
                arglist.add(args[i]);
            }
        }
    }

    private void addOpt(String opt, String key, String value) {
        opt_with_value.put(opt, key);
        values.put(key, value);
    }
    private String get(String key) {
        return values.get(key);
    }
    private Long getLong(String key) {
        String value = values.get(key);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    private double getDouble(String key, double defaultValue) {
        try {
            return Double.valueOf(values.get(key));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean hasOpt(String opt) {
        return opt_without_value.contains(opt);
    }

        
    /**
     * File search (easy glob)
     * @param directory directory path
     * @param pattern   searching file pattern with regular representation
     * @return matched file
     */
    private File searchFile(File directory, String pattern) {
        for(File file : directory.listFiles()) {
            if (file.getName().matches(pattern)) return file;
        }
        return null;
    }


    /**
     * Generate Language Profile from a text file.
     * 
     * <pre>
     * usage: --genprofile [text file] [language name]
     * </pre>
     * 
     */
    public void generateProfile() {
        File directory = new File(arglist.get(0));
        String lang = arglist.get(1);
        File file = searchFile(directory, lang + "wiki-.*-abstract\\.xml.*");
        if (file == null) {
            System.err.println("Not Found text file : lang = " + lang);
            return;
        }

        ObjectOutputStream os = null; //TODO this is wrong, not used. someone needs to close the FileOutputStream instead.
        try {
            LangProfile profile = GenProfile.load(lang, file);
            profile.omitLessFreq();
            new LangProfileWriter().write(profile, new FileOutputStream(new File(lang)));
        } catch (IOException e) {
            e.printStackTrace();
        } catch (LangDetectException e) {
            e.printStackTrace();
        } finally {
        	IOUtils.closeQuietly(os);
        }
    }

    /**
     * Language detection test for each file (--detectlang option)
     * 
     * <pre>
     * usage: --detectlang -d [profile directory] -a [alpha] [test file(s)]
     * </pre>
     * 
     */
    public void detectLang() throws IOException {
        LanguageDetector languageDetector = makeDetector();
        TextObjectFactory textObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText();

        for (String filename: arglist) {
            try (BufferedReader is = new BufferedReader(new InputStreamReader(new FileInputStream(filename), "utf-8"))) {
                TextObject textObject = textObjectFactory.create().append(is);
                List<DetectedLanguage> probabilities = languageDetector.getProbabilities(textObject);
                System.out.println(filename + ":" + probabilities);
            }
        }
    }


    /**
     * Batch Test of Language Detection (--batchtest option)
     * 
     * <pre>
     * usage: --batchtest -d [profile directory] -a [alpha] [test data(s)]
     * </pre>
     * 
     * The format of test data(s):
     * <pre>
     *   [correct language name]\t[text body for test]\n
     * </pre>
     *  
     */
    public void batchTest() throws IOException {
        LanguageDetector languageDetector = makeDetector();
        TextObjectFactory textObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText();

        Map<String, List<String>> result = new HashMap<>();
        for (String filename: arglist) {
            try (BufferedReader is = new BufferedReader(new InputStreamReader(new FileInputStream(filename), "utf-8"))) {
                while (is.ready()) {
                    String line = is.readLine();
                    int idx = line.indexOf('\t');
                    if (idx <= 0) continue;
                    String correctLang = line.substring(0, idx);
                    String text = line.substring(idx + 1);

                    TextObject textObject = textObjectFactory.forText(text);
                    Optional<String> lang = languageDetector.detect(textObject);
                    if (!result.containsKey(correctLang)) result.put(correctLang, new ArrayList<String>());
                    result.get(correctLang).add(lang.or("unknown"));
                    if (hasOpt("--debug")) System.out.println(correctLang + "," + lang + "," + (text.length() > 100 ? text.substring(0, 100) : text));
                }
            }

            List<String> langlist = new ArrayList<>(result.keySet());
            Collections.sort(langlist);

            int totalCount = 0, totalCorrect = 0;
            for ( String lang :langlist) {
                Map<String, Integer> resultCount = new HashMap<>();
                int count = 0;
                List<String> list = result.get(lang);
                for (String detectedLang: list) {
                    ++count;
                    if (resultCount.containsKey(detectedLang)) {
                        resultCount.put(detectedLang, resultCount.get(detectedLang) + 1);
                    } else {
                        resultCount.put(detectedLang, 1);
                    }
                }
                int correct = resultCount.containsKey(lang)?resultCount.get(lang):0;
                double rate = correct / (double)count;
                System.out.println(String.format("%s (%d/%d=%.2f): %s", lang, correct, count, rate, resultCount));
                totalCorrect += correct;
                totalCount += count;
            }
            System.out.println(String.format("total: %d/%d = %.3f", totalCorrect, totalCount, totalCorrect / (double) totalCount));
        }
    }



    /**
     * Using all language profiles from the given directory.
     */
    private LanguageDetector makeDetector() throws IOException {
        double alpha = getDouble("alpha", DEFAULT_ALPHA);

        String profileDirectory = get("directory") + "/";
        List<LanguageProfile> languageProfiles = new LanguageProfileReader().readAll(new File(profileDirectory));

        return LanguageDetectorBuilder.create(NgramExtractors.standard())
                .alpha(alpha)
                .shortTextAlgorithm(50)
                .withProfiles(languageProfiles)
                .build();
    }

}
