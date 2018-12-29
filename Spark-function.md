# Function

承接上一页关于Spark中的数据类型，这页主要讲一些主要函数操作。**这里假设我们使用dataframe进行操作，则需要查询相关的dataset的function。**

## groupby

Groups the Dataset using the specified columns, so we can run aggregation on them.  

See[RelationalGroupedDataset](https://spark.apache.org/docs/latest/api/scala/org/apache/spark/sql/RelationalGroupedDataset.html) for all the available aggregate functions. 

```scala
// Compute the average for all numeric columns grouped by department.
df.groupBy($"department").avg()
df.groupBy($"reorder").agg(count("reorder").alias("cnt"))

// Compute the max age and average salary, grouped by department and gender.
df.groupBy($"department", $"gender").agg(Map(
  "salary" -> "avg",
  "age" -> "max"
))
```

## orderBy和sort

**对于Spark来说两者是相同的！**

### 排序

```scala
df_final.filter(col("searchterm").startsWith(keyword_test)).sort("result".desc).show()
```

### 抽样

```scala
import org.apache.spark.sql.functions.rand
df_split.orderBy(rand()).limit(20).show()
df_split.sort(rand()).limit(20).show()
//rand Generate a random column with independent and identically distributed (i.i.d.) samples from U[0.0, 1.0].
```

## Select

### 返回子集

Selects a set of columns. 返回dataframe

```scala
// The following two are equivalent:
df.select("colA", "colB")
df.select($"colA", $"colB") //当无需对内容进行比较或运算时，可以省略掉$，$就代表dataframe的名字。可以看做有了$就会是一列了。
```

### 返回统计值

```scala
val df_variable = df_cal_save.select(mean(df_cal_save("hot")),stddev_samp(df_cal_save("hot")),mean(df_cal_save("burst")),stddev_samp(df_cal_save("burst")))
val (hot_mean, hot_std, burst_mean, burst_std) = (df_variable.first.getDouble(0), df_variable.first.getDouble(1), df_variable.first.getDouble(2), df_variable.first.getDouble(3))
```

##agg聚合函数

###返回统计值

```scala
// df.agg(...) is a shorthand for ds.groupBy().agg(...)
df.agg(max($"age"), avg($"salary"))
df.groupBy().agg(max($"age"), avg($"salary"))
```
## distinct

Returns a new Dataset that contains only the unique rows from this Dataset. 

```scala
val sessions_distinct = sessions.select("r","d").distinct()
```

## collect（搭配toMap实现dataframe转map）

Returns an array that contains all of Rows in this Dataset.

```scala
import spark.implicits._ //报错Unable to find encoder for type stored in a Dataset.  Primitive types (Int, String, etc) and Product types (case classes) are supported by importing spark.implicits._  Support for serializing other types will be added in future releases.
//val test = df_gamma.select(col("rd"), col("gamma")).collect.toMap
//报错Cannot prove that org.apache.spark.sql.Row <:< (T, U)
val test = df_gamma.select(col("rd"), col("gamma")).as[(String, Double)].collect.toMap //as转化为dataset的用途告诉后面的类型
```

## struct（将两列变成tuple，然后变成map；拆分成多列）

Creates a new struct column that composes multiple input columns. 

```scala
val df_gamma = sessions.select("r","d").distinct()
    .withColumn("rd", struct(col("r"), col("d")))
    .withColumn("gamma", lit(0.5))

val gamma = df_gamma.select(col("rd"), col("gamma")).as[((Int, Int), Double)].collect.toMap
```

直接新建某列就是struct类型。

```scala
var df_sessions_joined = df_sessions.withColumn("alpha", struct(lit(1.0), lit(2.0)))
```

```scala
+---+----+---+---+-----+---+---------+
|q  |u   |r  |d  |c    |cnt|alpha    |
+---+----+---+---+-----+---+---------+
|317|2909|7  |4  |false|1  |[1.0,2.0]|

root
 |-- q: long (nullable = true)
 |-- u: long (nullable = true)
 |-- r: integer (nullable = true)
 |-- d: integer (nullable = true)
 |-- c: boolean (nullable = true)
 |-- cnt: long (nullable = false)
 |-- alpha: struct (nullable = false)
 |    |-- col1: double (nullable = false)
 |    |-- col2: double (nullable = false)

StructType(StructField(q,LongType,true), StructField(u,LongType,true), StructField(r,IntegerType,true), StructField(d,IntegerType,true), StructField(c,BooleanType,true), StructField(cnt,LongType,false), StructField(alpha,StructType(StructField(col1,DoubleType,false), StructField(col2,DoubleType,false)),false))

```

拆分成多列，使用`select`和`_1`搭配

```scala
val df_sessions = df_sessions_distance.select($"query",explode($"click2distance").alias("group"))
    .withColumn("reorder", reorder($"query", $"group"))
    .groupBy("reorder").agg(count("reorder").alias("cnt"))
    .withColumn("q", $"reorder._1")
    .withColumn("u", $"reorder._2")
    .withColumn("r", $"reorder._3")
    .withColumn("d", $"reorder._4")
    .withColumn("c", $"reorder._5")
    .select("q", "u", "r", "d", "c", "cnt")
```

利用udf返回多列就是使用这个特点，先返回一个struct，然后再用select拆分

```scala
val update = udf{(r: Int, d: Int, c: Boolean, cnt: Long, alpha_uq: Double) =>
    val gamma_rd = gamma_br.value(r,d)
    if (!c)
    ((alpha_uq * (1 - gamma_rd) / (1 - alpha_uq * gamma_rd)) * cnt, cnt, (gamma_rd * (1 - alpha_uq) / (1 - alpha_uq * gamma_rd)) * cnt, cnt)
    else
    (alpha_uq * cnt, cnt, gamma_rd * cnt, cnt)
}

val df_sessions_update = df_sessions_joined.withColumn("update", update($"r", $"d", $"c", $"cnt", $"alpha"))
    .withColumn("alpha_numerator", $"update._1")
    .withColumn("alpha_denominator", $"update._2")
    .withColumn("gamma_numerator", $"update._3")
    .withColumn("gamma_denominator", $"update._4")
    .select($"q", $"u", $"r", $"d", $"c", $"cnt", $"alpha_numerator", $"alpha_denominator", $"gamma_numerator", $"gamma_denominator")
```

```scala

StructType(StructField(q,LongType,true), StructField(u,LongType,true), StructField(r,IntegerType,true), StructField(d,IntegerType,true), StructField(c,BooleanType,true), StructField(cnt,LongType,false), StructField(alpha,DoubleType,false), StructField(update,StructType(StructField(_1,DoubleType,false), StructField(_2,LongType,false), StructField(_3,DoubleType,false), StructField(_4,LongType,false)),true))
root
 |-- q: long (nullable = true)
 |-- u: long (nullable = true)
 |-- r: integer (nullable = true)
 |-- d: integer (nullable = true)
 |-- c: boolean (nullable = true)
 |-- cnt: long (nullable = false)
 |-- alpha: double (nullable = false)
 |-- update: struct (nullable = true)
 |    |-- _1: double (nullable = false)
 |    |-- _2: long (nullable = false)
 |    |-- _3: double (nullable = false)
 |    |-- _4: long (nullable = false)
```

## join

```scala
val df_sessions_joined = df_sessions.as("d1").join(df_sessions_alpha.as("d2"), ($"d1.q" === $"d2.q") && ($"d1.u" === $"d2.u"))
        .select($"d1.*", $"d2.alpha")
```



## Reference

- [dataset function](https://spark.apache.org/docs/2.1.0/api/scala/index.html#org.apache.spark.sql.Dataset)