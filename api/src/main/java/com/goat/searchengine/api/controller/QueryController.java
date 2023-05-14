package com.goat.searchengine.api.controller;

import com.goat.searchengine.api.document.WordDocument;
import com.goat.searchengine.api.model.Query;
import com.goat.searchengine.api.repository.Repository;
import com.mongodb.DBObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tartarus.snowball.ext.porterStemmer;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;


@RestController
public class QueryController {
    @Autowired
    private Repository repository;
    private int doc_count;
    private String stop_words;

    //max allowed gap between 2 words in phrase searching
    int phrase_gap = 10;

    public static String processStringWithStemming(String txt, String stopWords) {

        String processd_txt = "";
        txt = processStringWithoutStemming(txt, stopWords);
        porterStemmer stemmer = new porterStemmer();

        for (String iterator : txt.split(" ")) {
            stemmer.setCurrent(iterator);
            stemmer.stem();
            String stemed= stemmer.getCurrent();
            processd_txt += stemed + " ";
        }
        return processd_txt;
    }
    public static String processStringWithoutStemming(String txt, String stopWords) {

        String processd_txt = "";
        txt = txt.replaceAll("[^a-zA-Z0-9_ ]", "");
        txt = txt.replaceAll("\\s+", " ");
        txt = txt.toLowerCase();                                    //
        processd_txt = txt.replaceAll("\\b(" + stopWords + ")\\b\\s?", "");  // this wrapper for (word1 | word2 | ....)
        return processd_txt;
    }

    //return a string with the stop words
    public static String loadStopWords() {
        String stopWords = "";
        //try catch to check if the stop words file is opened correctly
        try {
            File stopWordsFile = new File("stopwords.txt");
            //Reading the first stop word
            try (Scanner sc = new Scanner(stopWordsFile)) {
                //Reading the first stop word
                if (sc.hasNext()) {
                    stopWords += sc.nextLine();
                }
                //Reading the remaining stop words
                while (sc.hasNext()) {
                    stopWords += "|" + sc.nextLine();
                }
            }
        } catch (FileNotFoundException e) {
            //Print an error message if an exception is thrown
            System.out.println("An error occurred.");
        }
        return stopWords;
    }

    String clean_paragraph(String paragraph, String target)
    {
        StringBuilder result = new StringBuilder();
        String[] words = paragraph.split(" ");
        int size = words.length;
        int index= -1;
        int current_index = 0;
        for(String word:words)
        {
            if(word.contains(target))
            {
                index= current_index;
                break;
            }
            current_index++;
        }
        if(index != -1)
        {
            int start = Math.max(index - 10, 0);
            int end = Math.min(index + 20,size);
            for(int i = start; i<end;i++)
            {
                result.append(words[i]).append(" ");
            }
        }
        return result.toString();
    }

    boolean check_phrase_gap(DBObject obj1,DBObject obj2)
    {
        List<Integer> positions1 = (List<Integer>)obj1.get("Word_Positions_In_this_Page");
        List<Integer> positions2 = (List<Integer>)obj2.get("Word_Positions_In_this_Page");
        for(int pos1:positions1)
        {
            for(int pos2:positions2)
            {
                if(pos2-pos1 <= phrase_gap && pos2-pos1 > 0)
                    return true;
            }
        }
        return false;
    }
    public List<Query> phrase_search(String text)
    {
        String processed_text = processStringWithoutStemming(text,stop_words);
        String[] words = processed_text.split(" ");
        HashMap<String, Pair<DBObject,Double>> map = new HashMap<>();
        List<Query> results = new ArrayList<>();
        //the page must satisfy this count to be returned to the user
        int must_have = words.length;

        //start ranking the pages
        for(String word:words) {
            WordDocument item = repository.findByWord(word);
            if(item != null) {
                List<DBObject> arr = item.getPagesContainingThisWord();
                //calculate idf for the word
                double doc_freq = item.getTotal_Appearance_in_All_Pages();
                double idf = Math.log(doc_count / doc_freq);

                for (DBObject obj : arr) {
                    String url = (String) obj.get("Page_URL");
                    double tf = (double) obj.get("Normalized_TF");
                    int tag_score = (int)obj.get("Score");

                    double tf_idf = tf * idf;
                    double final_score = tf_idf + tf_idf * tag_score;
                    double score = map.containsKey(url) ? map.get(url).getSecond() : 0;
                    DBObject current_obj = map.containsKey(url) ? map.get(url).getFirst() : null;


                    //choose the better paragraph to show based on words idf
                    if(current_obj != null)
                    {
                        //check if the positions for this word are after and not far from the previous one
                        if(!check_phrase_gap(current_obj,obj))
                            continue;
                        obj.put("count", (int) current_obj.get("count") + 1);
                        current_obj.put("count",(int) current_obj.get("count") + 1);

                        String current_paragraph = (String)current_obj.get("<p>");
                        Pair<DBObject, Double> pair;
                        if(current_paragraph.equals(""))
                        {
                            pair = Pair.of(obj, score + final_score);
                            obj.put("paragraph_score",idf);
                            obj.put("paragraph_word",word);
                        }
                        else
                        {
                            if(idf < (double)current_obj.get("paragraph_score"))
                                pair = Pair.of(current_obj, score + final_score);
                            else
                            {
                                pair = Pair.of(obj, score + final_score);
                                obj.put("paragraph_score",idf);
                                obj.put("paragraph_word",word);
                            }
                        }
                        map.put(url, pair);
                    }
                    else
                    {
                        // if the page has a paragraph, we set its score to compare it with later paragraphs and choose the best one
                        if(!((String)obj.get("<p>")).equals(""))
                        {
                            obj.put("paragraph_score",idf);
                            obj.put("paragraph_word",word);
                        }
                        obj.put("count", 1);
                        Pair<DBObject, Double> pair = Pair.of(obj, score + final_score);
                        map.put(url, pair);
                    }
                }
            }
        }
        for(Map.Entry<String,Pair<DBObject,Double>> entry : map.entrySet())
        {
            DBObject page_object = entry.getValue().getFirst();
            if((int)page_object.get("count") < must_have)
                continue;
            String desc = (String)page_object.get("Page_Description");
            String title = (String)page_object.get("Page_Title");
            String paragraph = "";
            if(!(((String) page_object.get("<p>")).equals("")))
            {
                paragraph = clean_paragraph((String)page_object.get("<p>"),(String) page_object.get("paragraph_word"));
            }
            String url = entry.getKey();
            double score = entry.getValue().getSecond();
            Query q = new Query(url,title,desc,paragraph,score);
            results.add(q);
        }
        Collections.sort(results);
        return results;
    }

    public List<Query> normal_search(String text)
    {
        String stemmed_text = processStringWithStemming(text,stop_words);
        String non_stemmed_text = processStringWithoutStemming(text,stop_words);

        String[] non_stemmed_words = non_stemmed_text.split(" ");
        String[] words = stemmed_text.split(" ");
        HashMap<String, Pair<DBObject,Double>> map = new HashMap<>();
        List<Query> results = new ArrayList<>();
        int word_index = 0;

        //start ranking the pages
        for(String word:words) {
            WordDocument[] items = repository.findByStemmedWord(word);
            for(WordDocument item : items) {
                List<DBObject> arr = item.getPagesContainingThisWord();

                //calculate idf for the word
                double doc_freq = item.getTotal_Appearance_in_All_Pages();
                double idf = Math.log(doc_count / doc_freq);

                for (DBObject obj : arr) {
                    String url = (String) obj.get("Page_URL");
                    double tf = (double) obj.get("Normalized_TF");
                    int tag_score = (int)obj.get("Score");
                    double tf_idf = tf * idf;
                    double final_score = tf_idf + tf_idf * tag_score;

                    //bonus score if it's the exact word and not a stem of it
                    if(item.getWord().equals(non_stemmed_words[word_index]))
                    {
                        final_score *= 100;
                    }
                    double score = map.containsKey(url) ? map.get(url).getSecond() : 0;
                    DBObject current_obj = map.containsKey(url) ? map.get(url).getFirst() : null;

                    //choose the better paragraph to show based on words idf
                    if(current_obj != null)
                    {
                        String current_paragraph = (String)current_obj.get("<p>");
                        Pair<DBObject, Double> pair;
                        if(current_paragraph.equals(""))
                        {
                            pair = Pair.of(obj, score + final_score);
                            obj.put("paragraph_score",idf);
                            obj.put("paragraph_word",word);
                        }
                        else
                        {
                            if(idf < (double)current_obj.get("paragraph_score"))
                                pair = Pair.of(current_obj, score + final_score);
                            else
                            {
                                pair = Pair.of(obj, score + final_score);
                                obj.put("paragraph_score",idf);
                                obj.put("paragraph_word",word);
                            }
                        }
                        map.put(url, pair);
                    }
                    else
                    {
                        //the page has a paragraph, so we set its score to compare it with later paragraphs and choose the best one
                        if(!((String)obj.get("<p>")).equals(""))
                        {
                            obj.put("paragraph_score",idf);
                            obj.put("paragraph_word",word);
                        }
                        Pair<DBObject, Double> pair = Pair.of(obj, score + final_score);
                        map.put(url, pair);
                    }
                }
            }
            word_index++;
        }
        for(Map.Entry<String,Pair<DBObject,Double>> entry : map.entrySet())
        {
            DBObject page_object = entry.getValue().getFirst();
            String desc = (String)page_object.get("Page_Description");
            String title = (String)page_object.get("Page_Title");
            String paragraph = "";
            if(!(((String) page_object.get("<p>")).equals("")))
            {
                paragraph = clean_paragraph((String)page_object.get("<p>"),(String) page_object.get("paragraph_word"));
            }
            String url = entry.getKey();
            double score = entry.getValue().getSecond();
            Query q = new Query(url,title,desc,paragraph,score);
            results.add(q);
        }
        Collections.sort(results);
        return results;
    }

    @GetMapping("/api")
public List<Query> search(String text)
{
    //get the total document count from the database
    LinkedHashMap meta = (LinkedHashMap) repository.getDocMetaData();
    doc_count = (int)meta.get("Doc_Cnt");

    //stem and then split the search text into words
    stop_words = loadStopWords();
    List<Query> results;
    //phrase searching
    if(text.contains("\""))
    {
        text = text.replaceAll("\"","");
        results = phrase_search(text);
    }
    //normal searching
    else
    {
        results = normal_search(text);
    }
    return results;
}
    @GetMapping("/lol")
    public List<WordDocument> test()
    {
        return repository.findAll();
    }
}
