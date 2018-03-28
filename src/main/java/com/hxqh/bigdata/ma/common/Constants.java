package com.hxqh.bigdata.ma.common;

/**
 * Created by Ocean lin on 2018/1/15.
 *
 * @author Lin
 */
public interface Constants {

    /**
     * Linux
     */
    String SPLIT_LABLE = "\\^";
    String FILE_PATH = "hdfs://spark1:9000/videos/";
    String FILE_SPLIT = "/";


//    /**
//     * Windows
//     */
//    String SPLIT_LABLE = "\\^";
//    String FILE_PATH = "E:\\";
//    String FILE_SPLIT = "\\";

    /**
     * 影视类别
     */
    String CATEGORY_MANDARIN = "国语";
    String CATEGORY_LOVE = "爱情";
    String CATEGORY_COMEDY = "喜剧";
    String CATEGORY_EUROPE = "欧洲";
    String CATEGORY_SCIENCE_FICTION = "科幻";
    String CATEGORY_FANTASY = "奇幻";
    String CATEGORY_SUSPENSE = "悬疑";
    String CATEGORY_USA = "美国";
    String CATEGORY_GOOD_REPUTATION = "口碑佳片";

    String CATEGORY_ACTION = "动作";
    String CATEGORY_WARFARE = "战争";
    String CATEGORY_ENGLISH = "英语";
    String CATEGORY_CHINESE = "华语";
    String CATEGORY_CINEMA = "院线";
    String CATEGORY_THRILLER = "惊悚";
    String CATEGORY_CRIME = "犯罪";
    String CATEGORY_GUN_BATTLE = "枪战";


    String HOST_SPARK3 = "spark3";
    Integer ES_PORT = 9300;
    String ES_PORT_STRING = "9200";

    String FILM_SPLIT_LABEL = ",";
    String FILM_SPLIT_SPACE = " ";


    Integer FILM_OFFSET_ACTOR = 9;
    Integer FILM_OFFSET_DIRECTOR = 3;
    Integer FILM_OFFSET_TITLE = 4;
    Integer FILM_OFFSET_PLAYNUM = 6;
    Integer FILM_OFFSET_SCORE = 7;
    Integer FILM_TOP_NUM = 10;


    String FILM_INDEX = "front_film";
    String FILM_TYPE = "film";


    String FILM_PLAYNUM = "film_playnum";
    String FILM_LABEL_PIE = "film_label_pie";
    String FILM_SCORE_NUM = "film_tit1e_score";
    String FILM_COMPANY = "film_tit1e_company";
    String FILM_ACTOR_PLAYNUM = "film_actor_playnum";
    String FILM_ACTOR_SCORE = "film_actor_score";
    String FILM_DIRECTOR_PLAYNUM = "film_director_playnum";
    String FILM_DIRECTOR_SCORE = "film_director_score";


    /**
     * soap
     */
    Integer SOAP_TOP_NUM = 10;

    String SOAP_PLAYNUM = "soap_playnum";
    String SOAP_LABEL_PIE = "soap_label_pie";
    String SOAP_SCORE_TITLE = "soap_score_title";

    String SOAP_GUEST_PLAYNUM = "soap_guest_playnum";
    String SOAP_GUEST_COMMENT = "soap_guest_comment";
    String SOAP_DIRECTOR_PLAYNUM = "soap_director_playnum";
    String SOAP_DIRECTOR_COMMENT = "soap_director_comment";

    /**
     * variety
     */
    Integer VARIETY_TOP_NUM = 10;


    String VARIETY_PLAYNUM = "variety_playnum";
    String VARIETY_LABEL_PIE = "variety_label_pie";
    String VARIETY_LABEL_PLAYNUM_PIE = "variety_playnum_label_pie";
    String VARIETY_GUEST_PALYNUM = "variety_guest_playnum";


    /**
     * book
     */
    Integer BOOK_TOP_NUM = 10;

    String BOOKS_ANALYSIS_INDEX = "front_book_index";
    String BOOKS_ANALYSIS_TYPE = "front_book_type";


    String BOOKS_COMMENT = "books_comment";
    String BOOKS_LABEL = "books_label";
    String BOOKS_PRESS = "books_press";


    /**
     * literature
     */
    Integer LITERATURE_TOP_NUM = 10;


    String LITERATURE_PLAYNUM = "literature_title_clicknum";
    String LITERATURE_LABEL_PIE = "literature_label_pie";
    String LITERATURE_LABEL_CLICKNUM_PIE = "literature_label_clicknum_pie";
    String LITERATURE_COMMENT_TITLE = "literature_comment_title";
    String LITERATURE_COMMENT_AUTHOR = "literature_comment_author";
    String LITERATURE_CLICKNUM_TITLE = "literature_clicknum_title";
    String LITERATURE_CLICKNUM_AUTHOR = "literature_clicknum_author";


}
