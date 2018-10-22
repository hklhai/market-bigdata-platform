package com.hxqh.bigdata.ma.spark

import java.io.IOException
import java.util.Date

import com.hxqh.bigdata.ma.common.Constants
import com.hxqh.bigdata.ma.domain.Soap
import com.hxqh.bigdata.ma.util.{DateUtils, ElasticSearchUtils, EsUtils}
import org.apache.spark.sql.SparkSession
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentFactory

/**
  * Created by Ocean lin on 2018/3/27.
  *
  * @author Ocean lin
  */
object MarketMaoYanSpark {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder.appName("MarketMaoYanSpark").getOrCreate
//    val spark = SparkSession.builder.master("local").appName("MarketMaoYanSpark").getOrCreate
    EsUtils.registerESTable(spark, "maoyan", "maoyan", "film")

    val sql = "select * from maoyan where addTime = ( select max(addTime) from maoyan ) "
    val maoyan = spark.sql(sql).rdd
    maoyan.cache
    val client = ElasticSearchUtils.getClient


    // 新增时间、          累计分账票房 、  电影名称、 来源、上映时间、排片场次、累计综合票房、实时分账票房、实时综合票房
    // 2018-03-26 21:00:00,2977.35,环太平洋：雷霆再起,maoyan,上映4天,118940,2800.3,4.13,4.39


    // 累计分账票房Top10
    var i = 1
    maoyan.map(e => (e.getFloat(1), e.getString(2))).sortByKey(false).take(Constants.FILM_TOP_NUM)
      .foreach(e => {
        addSoap(new Soap(new Date(), e._1.toDouble, e._2, Constants.MOVIE_SUM_SPLIT_BOX, i), client)
        i = i + 1
      })

    // 累计综合票房Top10
    i = 1
    maoyan.map(e => (e.getFloat(6), e.getString(2))).sortByKey(false).take(Constants.FILM_TOP_NUM)
      .foreach(e => {
        addSoap(new Soap(new Date(), e._1.toDouble, e._2, Constants.MOVIE_SUM_BOX, i), client)
        i = i + 1
      })

    // 实时分账票房Top10
    i = 1
    maoyan.map(e => (e.getFloat(7), e.getString(2))).sortByKey(false).take(Constants.FILM_TOP_NUM)
      .foreach(e => {
        var i = 1
        addSoap(new Soap(new Date(), e._1.toDouble, e._2, Constants.MOVIE_REALTIME_SPLIT_BOX, 1), client)
        i = i + 1
      })


    // 实时综合票房Top10
    i = 1
    maoyan.map(e => (e.getFloat(8), e.getString(2))).sortByKey(false).take(Constants.FILM_TOP_NUM)
      .foreach(e => {
        addSoap(new Soap(new Date(), e._1.toDouble, e._2, Constants.MOVIE_REALTIME_BOX, 1), client)
        i = i + 1
      })



    // 累计综合票房占比
    i = 1
    maoyan.map(e => (e.getFloat(6), e.getString(2))).distinct().collect()
      .foreach(e => {
        addSoap(new Soap(new Date(), e._1.toDouble, e._2, Constants.MOVIE_SUM_BOX_PIE, 1), client)
        i = i + 1
      })

    // 实时综合票房占比
    i = 1
    maoyan.map(e => (e.getFloat(8), e.getString(2))).distinct().collect()
      .foreach(e => {
        addSoap(new Soap(new Date(), e._1.toDouble, e._2, Constants.MOVIE_REALTIME_BOX_PIE, 1), client)
        i = i + 1
      })


    // 排片场次Top10
    i = 1
    maoyan.map(e => (e.getLong(5), e.getString(2))).sortByKey(false).take(Constants.FILM_TOP_NUM)
      .foreach(e => {
        addSoap(new Soap(new Date(), e._1.toDouble, e._2, Constants.MOVIE_SHOWINFO, 1), client)
        i = i + 1
      })


    // 排片场次占比
    i = 1
    maoyan.map(e => (e.getLong(5), e.getString(2))).sortByKey(false).collect()
      .foreach(e => {
        addSoap(new Soap(new Date(), e._1.toDouble, e._2, Constants.MOVIE_SHOWINFO_PIE, 1), client)
        i = i + 1
      })


    // todo
    // 豆瓣评分前10名

    // 各影片票房变化曲线
    // 各影片排片场次变化曲线


    /**
      *
      * @param soap   持久化的电视剧对象
      * @param client elasticsearch client
      */
    def addSoap(soap: Soap, client: TransportClient): Unit = try {
      val todayTime = DateUtils.getTodayTime
      val content = XContentFactory.jsonBuilder.startObject.
        field("numvalue", soap.numvalue).
        field("name", soap.name).
        field("category", soap.category).
        field("indexNumber", soap.indexNumber).
        field("addTime", todayTime).endObject

      client.prepareIndex(Constants.FILM_INDEX, Constants.FILM_TYPE).setSource(content).get
      println(soap.name + " Persist to ES Success!")
    } catch {
      case e: IOException =>
        e.printStackTrace()
    }


  }

}
