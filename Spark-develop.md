# Development

## 本地调试

```scala
object SparkUBM {
  def main(args: Array[String]): Unit = {
    System.setProperty("hadoop.home.dir", "E:\\hadoop-2.6.4") //设计hadoop home在windows下配置，https://github.com/srccodes/hadoop-common-2.2.0-bin/tree/master/bin中下载，下载winutils.exe文件复制到自己的E:\hadoop-2.6.4\bin目录里面
    val spark = SparkSession
      .builder()
      .master("local") //否则报错Exception in thread "main" org.apache.spark.SparkException: A master URL must be set in your configuration
      .appName("UBM")
      .getOrCreate()
    import spark.implicits._
    val data = spark.read.textFile("E:\\sparkdatatest.txt")
  }
}
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.music.search</groupId>
    <artifactId>UBM</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <scala.major.version>2.11</scala.major.version>
        <scala.version>2.11.8</scala.version>
        <spark.version>2.1.0</spark.version>
        <hadoop.version>2.7.1</hadoop.version>
    </properties>

    <dependencies>
        <dependency> <!-- Hadoop dependency -->
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-common</artifactId>
            <version>${hadoop.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-hdfs</artifactId>
            <version>${hadoop.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-mapreduce-client-core</artifactId>
            <version>${hadoop.version}</version>
        </dependency>

        <dependency> <!-- Spark dependency -->
            <groupId>org.apache.spark</groupId>
            <artifactId>spark-core_${scala.major.version}</artifactId>
            <version>${spark.version}</version>
        </dependency>

        <dependency> <!-- Spark dependency -->
            <groupId>org.apache.spark</groupId>
            <artifactId>spark-sql_${scala.major.version}</artifactId>
            <version>${spark.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.spark</groupId>
            <artifactId>spark-mllib_${scala.major.version}</artifactId>
            <version>${spark.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.spark</groupId>
            <artifactId>spark-hive_${scala.major.version}</artifactId>
            <version>${spark.version}</version>
        </dependency>

    </dependencies>

    <build>
        <plugins>

            <plugin>
                <groupId>org.scala-tools</groupId>
                <artifactId>maven-scala-plugin</artifactId>
                <version>2.15.2</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <source>1.8</source>
                    <target>1.8</target>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.19</version>
                <configuration>
                    <skip>true</skip>
                </configuration>
            </plugin>

        </plugins>
    </build>
</project>
```

##Hive连接

具体hive有很多配置，公司中为我们部署好了环境，节省了时间。
J这段代码比较重要，是官方的，见后文代码。
```scala
import java.io.File
val warehouseLocation = new File("spark-warehouse").getAbsolutePath
val spark = SparkSession
  .builder()
  .appName("Burst detection for day search count")
  .config("spark.sql.warehouse.dir", warehouseLocation)
  .enableHiveSupport()
  .getOrCreate()
// For implicit conversions like converting RDDs to DataFrames
import spark.implicits._
```

##代码
```scala
import scala.math.pow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import scala.collection.mutable.ListBuffer
import org.apache.spark.sql.expressions.{Window, WindowSpec}
import org.apache.spark.sql.Column
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.IntegerType //to solve the problem：Value toDF is not a member
import org.apache.spark.sql.SparkSession

case class TempRow(label: Int, dt: String)

object Burst {
  def main(args: Array[String]):Unit = {

    val warehouseLocation = new File("spark-warehouse").getAbsolutePath
    val spark = SparkSession
      .builder()
      .appName("Burst detection for day search count")
      .config("spark.sql.warehouse.dir", warehouseLocation)
      .enableHiveSupport()
      .getOrCreate()
    // For implicit conversions like converting RDDs to DataFrames
    import spark.implicits._
    spark.conf.set("spark.sql.shuffle.partitions", 2001)

    //set the date and interval
    val date_end = args(0)
    val period = 29
    val moving_average_length = 7
    //establish the date table
    val date_period = getDaysPeriod(date_end, period)
    val date_start = date_period.takeRight(1)(0)
    var date_list_buffer = new ListBuffer[TempRow]()
    for (dt <- date_period){
      date_list_buffer += TempRow(1, dt)
    }
    val date_list = date_list_buffer.toList
    val df_date = date_list.toDF
    df_date.createOrReplaceTempView("date_table")

    //extract the original data
    val sql_original=s"""select 1 as label, query, dt, sum(search_count) as cnt from (
select inputstring as query, dt, count(is_valid) as search_count, 'ard' as plat
from ddl.dt_search_ard_d
where dt >= '$date_start' and dt <= '$date_end'
and inputtype in ('1','2','3','4','6','7','8')
group by inputstring, dt
union all
select keyword as query, dt, count(valid) as search_count, 'pc' as plat
from ddl.dt_search_pc_d
where dt >= '$date_start' and dt <= '$date_end'
and inputtype in ('1','2','3','4','5','6','8')
group by keyword, dt
union all
select inputstring as query, dt, count(is_valid) as search_count, 'ios' as plat
from ddl.dt_search_ios_d
where dt >= '$date_start' and dt <= '$date_end'
and inputtype in ('1','2','3','4','6','7','8')
group by inputstring, dt
)triple_count
group by query, dt"""

    val df_original = spark.sql(sql_original)
    df_original.createOrReplaceTempView("keyword_30_sum")
    //change leftouterjoin to rightouterjoin
    val sql_thirty  ="select query, a.dt, (case when a.dt=b.dt then cnt else 0 end) as count from date_table a RIGHT OUTER JOIN keyword_30_sum b on a.label=b.label"
    val df_thirty = spark.sql(sql_thirty)
    //to eliminate the extra data
    val period_search = df_thirty.groupBy("query","dt").agg(max("count").as("cnt"))
    //use window function
    val weights = getWeight(moving_average_length)
    val index = List.range(0,moving_average_length)
//    val window_ma = Window.partitionBy("query").orderBy("query", "dt").rowsBetween(-6, 0)
    val window_ma = Window.partitionBy("query").orderBy(asc("dt"))
    //val period_ma = period_search.withColumn("movingAvg", avg(period_search("cnt")).over(window_ma))
    val period_ma = period_search.withColumn("weightedmovingAvg", weighted_average(index, weights, window_ma, period_search("cnt"))).withColumn("simplemovingAvg", avg(period_search("cnt")).over(window_ma.rowsBetween(-6, 0)))
    // set filter the last n rows
    val window_filter = Window.partitionBy("query").orderBy(asc("dt"))
    val period_filter = period_ma.withColumn("filter_tag", row_number.over(window_filter)).filter($"filter_tag" >= moving_average_length)

    //calculate the amp score
//    val period_cal = period_ma.groupBy("query").agg((last("simplemovingAvg")-(mean("simplemovingAvg") + lit(1.5)*stddev_samp("simplemovingAvg"))).as("simpleamp"), (last("weightedmovingAvg")-(mean("weightedmovingAvg") + lit(1.5)*stddev_samp("weightedmovingAvg"))).as("weightedamp"))
    val period_cal = period_filter.groupBy("query").agg((first("simplemovingAvg")-(mean("simplemovingAvg") + lit(1.5)*stddev_samp("simplemovingAvg"))).as("simpleamp"), (last("weightedmovingAvg")-(mean("weightedmovingAvg") + lit(1.5)*stddev_samp("weightedmovingAvg"))).as("weightedamp"))

    //format the data to save
//    val df_save = period_cal.filter($"amp"> lit(100)).withColumn("ampTmp", $"amp".cast(IntegerType)).drop("amp").withColumnRenamed("ampTmp", "amp")
    val df_save = period_cal.filter($"simpleamp" > lit(50) || $"weightedamp" > lit(50)).withColumn("sampTmp", $"simpleamp".cast(IntegerType)).drop("simpleamp").withColumnRenamed("sampTmp", "simpleamp").withColumn("wampTmp", $"weightedamp".cast(IntegerType)).drop("weightedamp").withColumnRenamed("wampTmp", "weightedamp")
    df_save.createOrReplaceTempView("savetable")
    //save the data
    val hive_table = args(1)
    val sql_save = s"INSERT OVERWRITE TABLE $hive_table PARTITION(dt='$date_end') select * from savetable"
    //without it, it will return Dynamic partition strict mode requires at least one static partition column
    spark.sql(sql_save)
    //it doesn't matter:ERROR KeyProviderCache: Could not find uri with key
    spark.stop() //to avoid ERROR LiveListenerBus: SparkListenerBus has already stopped! Dropping event SparkListenerExecutorMetricsUpdate
  }

  // get the period days under date_start and interval in List[String] format
  def getDaysPeriod(dt: String, interval: Int): List[String] = {
    var period = new ListBuffer[String]() //initialize the return List period
    period += dt
    val cal: Calendar = Calendar.getInstance() //reset the date in Calendar
    cal.set(dt.split("-")(0).toInt, dt.split("-")(1).toInt - 1, dt.split("-")(2).toInt)
    val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd") //format the output date
    for (i <- 0 to interval - 1){
      cal.add(Calendar.DATE, - 1)
      period += dateFormat.format(cal.getTime())
    }
    period.toList
  }
  def getWeight(length: Int): List[Double]= {
    var sum = 0.0
    for (i <- 0 to length-1 ){
      sum += pow(0.5, i)
    }
//    val weights = for (i <- 0 to length-1 ) yield pow(0.5, i)/sum // it will return scala.collection.immutable.IndexedSeq
    val weights = for (i <- List.range(0, length) ) yield pow(0.5, i)/sum
//    var weights_buffer = new ListBuffer[Double]()
//    for (i <- 0 to length-1 ){
//      weights_buffer += pow(0.5, i)/sum
//    }
//    val weights = weights_buffer.toList
    weights
  }
  def weighted_average(index: List[Int], weights: List[Double], w: WindowSpec, c: Column): Column= {
    val wma_list = for (i <- index) yield (lag(c, i).over(w))*weights(i) // list comprehension, map also can do some easy thing, return scala.collection.immutable.IndexedSeq
    wma_list.reduceLeft(_ + _)
  }
}
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.music.search</groupId>
    <artifactId>burst_detection</artifactId>
    <version>1.0</version>


    <properties>
        <scala.major.version>2.11</scala.major.version>
        <scala.version>2.11.8</scala.version>
        <spark.version>2.1.0</spark.version>
        <hadoop.version>2.7.1</hadoop.version>
    </properties>

    <dependencies>
        <dependency> <!-- Hadoop dependency -->
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-common</artifactId>
            <version>${hadoop.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-hdfs</artifactId>
            <version>${hadoop.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-mapreduce-client-core</artifactId>
            <version>${hadoop.version}</version>
        </dependency>

        <dependency> <!-- Spark dependency -->
            <groupId>org.apache.spark</groupId>
            <artifactId>spark-core_${scala.major.version}</artifactId>
            <version>${spark.version}</version>
        </dependency>

        <dependency> <!-- Spark dependency -->
            <groupId>org.apache.spark</groupId>
            <artifactId>spark-sql_${scala.major.version}</artifactId>
            <version>${spark.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.spark</groupId>
            <artifactId>spark-mllib_${scala.major.version}</artifactId>
            <version>${spark.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.spark</groupId>
            <artifactId>spark-hive_${scala.major.version}</artifactId>
            <version>${spark.version}</version>
        </dependency>

    </dependencies>

    <build>
        <plugins>

            <plugin>
                <groupId>org.scala-tools</groupId>
                <artifactId>maven-scala-plugin</artifactId>
                <version>2.15.2</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <source>1.8</source>
                    <target>1.8</target>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.19</version>
                <configuration>
                    <skip>true</skip>
                </configuration>
            </plugin>

        </plugins>
    </build>

</project>
```