package com.hxqh.bigdata.ma.spark

import java.io.IOException

import com.hxqh.bigdata.ma.common.Constants
import com.hxqh.bigdata.ma.domain.Literature
import com.hxqh.bigdata.ma.util.{DateUtils, ElasticSearchUtils, EsUtils}
import org.apache.spark.sql.SparkSession
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentFactory

/**
  * Created by Ocean lin on 2018/3/22.
  *
  * @author Ocean lin
  */
object MarketLiteratureSpark {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder.appName("MarketLiteratureSpark").getOrCreate
    // val spark = SparkSession.builder.master("local").appName("MarketLiteratureSpark").getOrCreate
    EsUtils.registerESTable(spark, "literature", "market_literature", "literature")
    val startDate = DateUtils.getYesterdayDate();
    val endDate = DateUtils.getTodayDate();

    val sql = "select * from literature where addTime>='" + startDate + "' and addTime<= '" + endDate + "'"
    val literature = spark.sql(sql).rdd
    literature.cache
    val client = ElasticSearchUtils.getClient

    //                     author  clicknum  commentnum  label  mainclass  name     subclass
    // 2018-04-02 15:22:38,火影忍者,14513,3,0,爽文 热血 英雄无敌 生活,男频,特战狂兵,17k,都市小说
    // 累计点击量最多作品排名Top10
    literature.distinct().filter(e => (e.get(2) != null)).map(e => (e.getString(7), e.getLong(2))).reduceByKey(_ + _).map(e => (e._2, e._1))
      .sortByKey(false).take(Constants.LITERATURE_TOP_NUM).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_PLAYNUM), client)
    })

    //  各标签占比情况
    literature.distinct().filter(e => (e.get(2) != null)).filter(e => (e.get(5) != null)).flatMap(e => {
      val splits = e.getString(5).split(" ")
      for (i <- 0 until splits.length - 1)
        yield (splits(i), 1)
    }).reduceByKey(_ + _).map(e => (e._2, e._1)).sortByKey(false).take(15).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_LABEL_PIE), client)
    })

    // 各标签点击量占比
    literature.distinct().filter(e => (e.get(2) != null)).filter(e => (e.get(5) != null)).flatMap(e => {
      val splits = e.getString(5).split(Constants.FILM_SPLIT_SPACE)
      for (i <- 0 until splits.length - 1)
        yield (splits(i), e.getLong(2))
    }).reduceByKey(_ + _).map(e => (e._2, e._1)).sortByKey(false).take(15).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_LABEL_CLICKNUM_PIE), client)
    })


    // 累计评论量最多作品排名Top10
    literature.distinct().filter(e => (e.get(3) != null)).map(e => (e.getString(7), e.getInt(3))).reduceByKey(_ + _).
      map(e => (e._2, e._1)).sortByKey(false).take(Constants.LITERATURE_TOP_NUM).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_COMMENT_TITLE), client)
    })

    // 累计评论量最多作者排名Top10
    literature.distinct().filter(e => (e.get(3) != null)).map(e => (e.getString(1), e.getInt(3))).reduceByKey(_ + _).
      map(e => (e._2, e._1)).sortByKey(false).take(Constants.LITERATURE_TOP_NUM)
      .foreach(e => {
        addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_COMMENT_AUTHOR), client)
      })

    // 累计点击量最多作者排名Top10
    literature.distinct().filter(e => (e.get(2) != null)).map(e => (e.getString(1), e.getLong(2))).reduceByKey(_ + _).
      map(e => (e._2, e._1)).sortByKey(false).take(Constants.LITERATURE_TOP_NUM).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_CLICKNUM_AUTHOR), client)
    })


    // subclass 点击量排名占比
    literature.distinct().filter(e => (e.get(2) != null)).map(e => (e.getString(9), e.getLong(2))).reduceByKey(_ + _).map(e => (e._2, e._1))
      .sortByKey(false).take(15).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_CLICKNUM_SUBCLASS), client)
    })

    // subclass 占比
    literature.distinct().map(e => (e.getString(9), 1)).reduceByKey(_ + _).map(e => (e._2, e._1)).sortByKey(false).take(15).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_SUBCLASS_PIE), client)
    })


    // todo
    // 周度、月度各标签类别点击量变化曲线
  }

  /**
    *
    * @param literature 持久化的网络文学对象
    * @param client     elasticsearch client
    */
  def addLiterature(literature: Literature, client: TransportClient): Unit = try {
    val todayTime = DateUtils.getTodayTime
    val content = XContentFactory.jsonBuilder.startObject.
      field("numvalue", literature.numvalue).
      field("name", literature.name).
      field("category", literature.category).
      field("addTime", todayTime).endObject

    client.prepareIndex(Constants.BOOKS_ANALYSIS_INDEX, Constants.BOOKS_ANALYSIS_TYPE).setSource(content).get
    println(literature.name + " Persist to ES Success!")
  } catch {
    case e: IOException =>
      e.printStackTrace()
  }
}
