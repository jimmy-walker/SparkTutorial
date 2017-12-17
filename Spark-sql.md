# Spark sql

## Spark sql需要分段执行

```scala
//亲测能够直接成功，因为是action，无需等待。
spark.sql("use temp")
spark.sql("DROP TABLE IF EXISTS temp.jdummy_dt")
spark.sql("create table temp.jdummy_dt(dt string)")
```

## 操作外表

###要么直接create后，insert到数据仓库中，成为一张新表
```scala
spark.sql("insert into table temp.jdummy_dt values(1, '2017-11-29')") //注意不能values后有空格，否则会报错：ERROR KeyProviderCache: Could not find uri with key
```

###要么直接先对dataframe进行cache，然后再生成临时表进行使用（第一次时会仍然进行操作的），但是之后会加快速度。

```scala
case class TempRow(label: Int, dt: String)
val date_period = getDaysPeriod(date_end, period)
val date_start = date_period.takeRight(1)(0)
var date_list_buffer = new ListBuffer[TempRow]()
for (dt <- date_period){
    date_list_buffer += TempRow(1, dt)
}
val date_list = date_list_buffer.toList
val df_date = date_list.toDF
df_date.cache
df_date.createOrReplaceTempView("date_table")
```