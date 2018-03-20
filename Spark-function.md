# Function

承接上一页关于Spark中的数据类型，这页主要讲一些主要函数操作。

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

