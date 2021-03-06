package cf

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}
import base._



object CollaborativeFilterItemBased_modify
{

  def itemRecommendAndSimilarCompute(args:Array[String]):Unit = {
    if(args.length!=6){
      System.err.println("Usage: CollaborativeFilterItemBased_modify <StoreOriginalRecommendHdfsDir> <similarHdfsDir> <recommendHdfsDir> <userPrefNum> <similar-number> <recommend-number>")
      System.exit(1)
    }

    val cale = Calendar.getInstance
    val caledate = cale.getTime
    val dateformat = new SimpleDateFormat("yyyy-MM-dd")
    val date = dateformat.format(caledate)

    val sparkConf = new SparkConf().setAppName("cf item-based")
//    sparkConf.set("spark.cores.max","25")
//    sparkConf.set("spark.executor.memory","2G")
    sparkConf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    sparkConf.registerKryoClasses(Array(classOf[RelationAndScore],classOf[RelationRatingsWithSize],classOf[TwoSimilarUserOrIrem],
      classOf[TempCalcStating],classOf[ReduceTempCalcStating]))

    val sc = new SparkContext(sparkConf)

    val hiveql = new HiveContext(sc)
    import hiveql.implicits._


    // extract (userid, itemid, rating) from ratings data
    val dataHdfs = args(0)+"/vdate="+date
    val oriRatings = sc.textFile(dataHdfs,100).map(line => {
      val fields = line.split("\t")
      RelationAndScore(fields(0).toLong, fields(1).toLong, fields(2).toDouble)
    })

//    oriRatings.persist(StorageLevel.MEMORY_AND_DISK_SER)

    //filter redundant (user,item,rating),this set user favorite (best-loved) 100 item

    val userPrefNum = args(3).toInt
    val ratings = oriRatings.groupBy(k=>k.userid).flatMap(x=>(x._2.toList.sortWith((x,y)=>x.score>y.score).take(userPrefNum)))

//    ratings.persist(StorageLevel.MEMORY_AND_DISK_SER)

    // get num raters per item, keyed on item id,,item2manyUser formating as  RDD[(Long, scala.Iterable[RelationAndScore])]
    val item2manyUser = ratings.groupBy(x => x.beuserid)


    // join ratings with num raters on item id,,ratingsWithSize formating as (user,item,rating,numRaters)

    //################### optimizing  procedure ############
    //    val numRatersPerItem = item2manyUser.map(grouped => (grouped._1, grouped._2.size))
    //    val ratingsWithSize = item2manyUser.join(numRatersPerItem).
    //      flatMap(joined => {
    //        joined._2._1.map(f => (f._1, f._2, f._3, joined._2._2))
    //    })

    val ratingsWithSize = item2manyUser.flatMap(x=>{
      x._2.map(y=>RelationRatingsWithSize(y.userid,y.beuserid,y.score,x._2.size))
    })

    // ratingsWithSize now contains the following fields: (user, item, rating, numRaters).

    // dummy copy of ratings for self join ,formating as
    val ratings2 = ratingsWithSize.keyBy(x => x.userid)

    // join on userid and filter item pairs such that we don't double-count and exclude self-pairs

    //***计算半矩阵，减少计算量
    val ratingPairs =ratings2.join(ratings2).filter(f => f._2._1.beuserid < f._2._2.beuserid)
//    ratingPairs.persist(StorageLevel.MEMORY_AND_DISK_SER)

    // compute raw inputs to similarity metrics for each item pair
    val tempVectorCalcs =
      ratingPairs.map(data => {
        val key = TwoSimilarUserOrIrem(data._2._1.beuserid, data._2._2.beuserid)
        val stats =
          TempCalcStating(data._2._1.score * data._2._2.score, // rating 1 * rating 2
            data._2._1.score,                // rating item 1
            data._2._2.score,                // rating item 2
            math.pow(data._2._1.score, 2),   // square of rating item 1
            math.pow(data._2._2.score, 2),   // square of rating item 2
            data._2._1.size,                // number of raters item 1
            data._2._2.size)                // number of raters item 2
        (key, stats)
      })

//    tempVectorCalcs.persist(StorageLevel.MEMORY_AND_DISK_SER)
//    ratingPairs.unpersist()

    val vectorCalcs = tempVectorCalcs.groupByKey().map(data => {
            val key = data._1
            val vals = data._2
            val size = vals.size
            val dotProduct = vals.map(f => f.dotProduct).sum
            val ratingSum = vals.map(f => f.rating1).sum
            val rating2Sum = vals.map(f => f.rating2).sum
            val ratingSq = vals.map(f => f.rating1Sq).sum
            val rating2Sq = vals.map(f => f.rating2Sq).sum
            val numRaters = vals.map(f => f.rating1Num).max
            val numRaters2 = vals.map(f => f.rating2Num).max
            (key, ReduceTempCalcStating(size, dotProduct, ratingSum, rating2Sum, ratingSq, rating2Sq, numRaters, numRaters2))
          })
//    vectorCalcs.persist(StorageLevel.MEMORY_AND_DISK_SER)
//    tempVectorCalcs.unpersist()

    //improvedSimilar (dotProduct:Double,ratingNorm:Double,rating2Norm:Double,usersInCommon:Double,totalUsers1:Double,totalUsers2:Double)
    val tempSimilarities =
      vectorCalcs.map(fields => {
        val key = fields._1
//        val jaccardSim = jaccardSimilarity(fields._2.size,fields._2.rating1Num,fields._2.rating2Num)
        val improvedSim = improveSimilarity(fields._2.dotProductSum,math.sqrt(fields._2.rating1SquareSum),math.sqrt(fields._2.rating2SquareSum),
                            fields._2.commonSize,fields._2.rating1Num,fields._2.rating2Num)
        RelationAndScore(key.userid1,key.userid2,improvedSim)
      })

    val totalSimilarities = tempSimilarities ++ (tempSimilarities.map(x=>RelationAndScore(x.beuserid,x.userid,x.score)))

    val similarNumber = args(4).toInt

    val similarities = totalSimilarities.groupBy(x=>x.userid).flatMap(x=>{
      //       val tempMaxSim = x._2.map(a=>a._2).max
      val sim = x._2.toList.sortWith((a,b)=>a.score>b.score).take(similarNumber)
      sim
    })

//    similarities.persist(StorageLevel.MEMORY_AND_DISK_SER)
//    vectorCalcs.unpersist()

    val similarHdfsDir = args(1)
    similarities.map(x=>{
      val temp = new BigDecimal(x.score).setScale(4,RoundingMode.HALF_UP).doubleValue
      x.userid + "\t" + x.beuserid + "\t" + temp
    }).saveAsTextFile(similarHdfsDir+"/vdate="+date)

    val ratingsInverse = ratings.groupBy(x=>x.beuserid)
    val similaritiesGroupBy = similarities.groupBy(x=>x.userid)

    //  statistics format ((user,item),(sim,sim*rating)),,,, ratingsInverse.join(similarities) fromating as (Item,((user,rating),(item,similar)))
    //RDD[(Long, (RelationAndScore, Iterable[RelationAndScore]))

    //RDD[(Long, (Iterable[RelationAndScore], Iterable[RelationAndScore]))]
    val statistics = ratingsInverse.join(similaritiesGroupBy).
      flatMap(x=>x match {
        case (a,(b,c))=>{
          b.flatMap(x=>{
            c.map(y=>(TwoSimilarUserOrIrem(x.userid,y.beuserid),(y.score,x.score*y.score,1.0)))
          })
        }
      })

//    statistics.persist(StorageLevel.MEMORY_AND_DISK_SER)
//    similarities.unpersist()

//    val statistics = ratingsInverse.join(similaritiesGroupBy).
//      flatMap(x=>x._2._2.map(a=>(TwoSimilarUserOrIrem(x._2._1.userid,a.beuserid),(a.score,x._2._1.score*a.score,1.0))))

    // predictResult fromat ((user,item),predict)
//    val predictResult = statistics.reduceByKey((x,y)=>((x._1+y._1),(x._2+y._2))).map(x=>(TwoSimilarUserOrIrem(x._1.userid1,x._1.userid2),x._2._2/x._2._1))
    val predictResult = statistics.reduceByKey((x,y)=>((x._1+y._1),(x._2+y._2),(x._3+y._3))).map(x=>(x._1,if(x._2._3<4) x._2._2 else x._2._2/x._2._1))
//    predictResult.persist(StorageLevel.MEMORY_AND_DISK_SER)
//    statistics.unpersist()


    val filterItem = oriRatings.map(x=>(TwoSimilarUserOrIrem(x.userid,x.beuserid),x.score))


    val recommendNum = args(5).toInt

    val filterResult = predictResult.subtractByKey(filterItem).map(x=>RelationAndScore(x._1.userid1,x._1.userid2,x._2)).
      groupBy(x=>x.userid).flatMap(x=>(x._2.toList.sortWith((a,b)=>a.score>b.score).take(recommendNum)))

//    val totalScore = predictResult ++ filterItem
//    val filterResult = totalScore.reduceByKey(_+_).filter(x=> !(x._2 equals(Double.NaN))).map(x=>RelationAndScore(x._1.userid1,x._1.userid2,x._2)).
//      groupBy(x=>x.userid).flatMap(x=>(x._2.toList.sortWith((a,b)=>a.score>b.score).take(recommendNum)))


    //  RDD[(Long, (((Long, Long), Double), Long))]
    //      val modifyResultSorted = tempFilterResult.keyBy(x=>x._2).leftOuterJoin(viewStatisticData)
    //      val finalResult = modifyResultSorted.map(x=>(x._2._1._1,x._2._1._2,x._2._1._3/x._2._2.getOrElse(1.toLong)))

    val recommendHdfsDir = args(2)
    filterResult.map(x=>{
      val temp = new BigDecimal(x.score).setScale(4,RoundingMode.HALF_UP).doubleValue
      x.userid+ "\t" + x.beuserid + "\t" + temp
    }).saveAsTextFile(recommendHdfsDir+"/vdate="+date)

    sc.stop()
  }

  def main(args: Array[String]) {
    itemRecommendAndSimilarCompute(args)
  }
 
  // *************************
  // * SIMILARITY MEASURES
  // *************************
 
  /**
   * The correlation between two vectors A, B is
   *   cov(A, B) / (stdDev(A) * stdDev(B))
   *
   * This is equivalent to
   *   [n * dotProduct(A, B) - sum(A) * sum(B)] /
   *     sqrt{ [n * norm(A)^2 - sum(A)^2] [n * norm(B)^2 - sum(B)^2] }
   */
  def correlation(size : Double, dotProduct : Double, ratingSum : Double,
                  rating2Sum : Double, ratingNormSq : Double, rating2NormSq : Double) = {
 
    val numerator = size * dotProduct - ratingSum * rating2Sum
    val denominator = scala.math.sqrt(size * ratingNormSq - ratingSum * ratingSum) *
      scala.math.sqrt(size * rating2NormSq - rating2Sum * rating2Sum)
 
    numerator / denominator
  }
 
  /**
   * Regularize correlation by adding virtual pseudocounts over a prior:
   *   RegularizedCorrelation = w * ActualCorrelation + (1 - w) * PriorCorrelation
   * where w = # actualPairs / (# actualPairs + # virtualPairs).
   */
  def regularizedCorrelation(size : Double, dotProduct : Double, ratingSum : Double,
                             rating2Sum : Double, ratingNormSq : Double, rating2NormSq : Double,
                             virtualCount : Double, priorCorrelation : Double) = {
 
    val unregularizedCorrelation = correlation(size, dotProduct, ratingSum, rating2Sum, ratingNormSq, rating2NormSq)
    val w = size / (size + virtualCount)
 
    w * unregularizedCorrelation + (1 - w) * priorCorrelation
  }
 
  /**
   * The cosine similarity between two vectors A, B is
   *   dotProduct(A, B) / (norm(A) * norm(B))
   */
  def cosineSimilarity(dotProduct : Double, ratingNorm : Double, rating2Norm : Double) = {
    dotProduct / (ratingNorm * rating2Norm)
  }
 
  /**
   * The Jaccard Similarity between two sets A, B is
   *   |Intersection(A, B)| / |Union(A, B)|
   */
  def jaccardSimilarity(usersInCommon : Double, totalUsers1 : Double, totalUsers2 : Double) = {
    val union = totalUsers1 + totalUsers2 - usersInCommon
    usersInCommon / union
  }

  /**
   * this similar base on cosine and jaccard,and format is
   *  dotProduct(A, B) / (norm(A) * norm(B))*|Intersection(A, B)| / |Union(A, B)|
   */
  def improveSimilarity(dotProduct:Double,ratingNorm:Double,rating2Norm:Double,usersInCommon:Double,totalUsers1:Double,totalUsers2:Double):Double = {
    val cosineSim = dotProduct / (ratingNorm * rating2Norm)
    val jaccardSim = usersInCommon/(totalUsers1+totalUsers2-usersInCommon)
    cosineSim*jaccardSim
  }
}