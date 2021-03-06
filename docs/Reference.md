# Reference
After running your first [examples](GettingStarted.md) this page will give you more details on all available options and the core concepts behind Smart Data Lake.

## Configuration - HOCON
The configuration files are stored in the [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md) file format.
The main sections are global, connections, data objects and actions. 
As a starting point, use the [application.conf](https://github.com/smart-data-lake/sdl-examples/blob/develop/src/main/resources/application.conf) from SDL-examples. 
More details and options are described below. 

### User and Password Variables
Usernames and passwords should not be stored in you configuration files in clear text as these files are often stored directly in the version control system.
They should also not be visible in logfiles after execution.
Instead of having the username and password directly, use the following conventions:

Pattern|Meaning
---|---
CLEAR#pd|The variable will be used literally (cleartext). This is only recommended for test environments.
ENV#pd|The values for this variable will be read from the environment variable called "pd". 
DBSECRET#scope.pd|Used in a Databricks environment, i.e. Microsoft Azure. Expects the variable "pd" to be stored in the given scope.

### Local substitution
Local substitution allows to reuse the id of a configuration object inside its attribute definitions by the special token "~{id}". See the following example:
```
dataObjects [
  dataXY {
    type = HiveTableDataObject
    path = "/data/~{id}"
    table {
      db = "default"
      name = "~{id}"
    }
  }
}
```
Note: local substitution only works in a fixed set of attributes defined in Environment.configPathsForLocalSubstitution.

## Global Options
The global section of the configuration is mainly used to set spark options used by all executions.
A good list with examples can be found in the [application.conf](https://github.com/smart-data-lake/sdl-examples/blob/develop/src/main/resources/application.conf) of sdl-examples.

## Connections
Some Data Objects need a connection, e.g. JdbcTableDataObject, as they need to know how to connect to a database.
Instead of defining the connection information for every data object, you can conveniently define it in one place and just use the reference in the data objects.
The possible parameters depend on the connection type. Please note the section on [usernames and password](#user-and-password-variables).

For a list of all available connections, please consult the [API docs](site/scaladocs/io/smartdatalake/workflow/connection/package.html) directly.

In the package overview, you can also see the parameters available to each type of connection and which parameters are optional.

## Data Objects
For a list of all available data objects, please consult the [API docs](site/scaladocs/io/smartdatalake/workflow/dataobject/package.html) directly.
In the package overview, you can also see the parameters available to each type of data object and which parameters are optional.

Data objects are structured in a hierarchy as many attributes are shared between them, i.e. do Hive tables and transactional tables share common attributes modeled in TableDataObject. 

Here is an overview of all data objects:
![data object hierarchy](images/dataobject_hierarchy.png)

## Actions
For a list of all available actions, please consult the [API docs](site/scaladocs/io/smartdatalake/workflow/action/package.html) directly.

In the package overview, you can also see the parameters available to each type of action and which parameters are optional.


# Command Line
SmartDataLakeBuilder is a java application. To run on a cluster with spark-submit use **DefaultSmartDataLakeBuilder** application.
It can be started with the following command line (details see [YARN](YARN.md))
```bash
spark-submit --master yarn --deploy-mode client --class io.smartdatalake.app.DefaultSmartDataLakeBuilder target/smartdatalake_2.11-1.0.3-jar-with-dependencies.jar [arguments]
```
and takes the following arguments:
```
Usage: DefaultSmartDataLakeBuilder [options]
  -f, --feed-sel <value>   Regex pattern to select the feed to execute.
  -n, --name <value>       Optional name of the application. If not specified feed-sel is used.
  -c, --config <value>     One or multiple configuration files or directories containing configuration files, separated by comma.
  --partition-values <value>
                           Partition values to process in format <partitionColName>=<partitionValue>[,<partitionValue>,...].
  --multi-partition-values <value>
                           Multi partition values to process in format <partitionColName1>=<partitionValue>,<partitionColName2>=<partitionValue>[;(<partitionColName1>=<partitionValue>,<partitionColName2>=<partitionValue>;...].
  --parallelism <value>    Parallelism for DAG run.
  --state-path <value>     Path to save run state files. Must be set to enable recovery in case of failures.
  --override-jars <value>  Comma separated list of jars for child-first class loader. The jars must be present in classpath.
  --test <value>           Run in test mode: config -> validate configuration, dry-run -> execute prepare- and init-phase only to check environment and spark lineage
  --help                   Display the help text.
  --version                Display version information.
```
There exists the following adapted applications versions:
- **LocalSmartDataLakeBuilder**:<br>default for Spark master is `local[*]` and it has additional properties to configure Kerberos authentication. Use this application to run in a local environment (e.g. IntelliJ) without cluster deployment.  
- **DatabricksSmartDataLakeBuilder**:<br>see [MicrosoftAzure](MicrosoftAzure.md)


# Concepts

## DAG

### Execution phases - early validation
Execution of a SmartDataLakeBuilder run is designed with "early validation" in mind. This means it tries to fail as early as possible if something is wrong. 
Execution therefore involves the following phases.
1. Parse configuration: configuration is parsed and validated 
2. DAG prepare: Preconditions are validated. This includes testing Connections and DataObject structures which must exists. 
3. DAG init: Lineage of Actions according to the DAG is created and validated. For Spark Actions this involves the validation of the DataFrame lineage. A column which doesn't exist but is referenced in a later Action will fail the execution.    
4. DAG exec: Execution of Actions, data is effectively (and only) transferred during this phase.

## Execution modes
Execution modes select the data to be processed. By default, if you start SmartDataLakeBuilder, there is no filter applied. This means every Action reads all data from its input DataObjects.

You can set an execution mode by defining attribute "executionMode" of an Action. Define the chosen ExecutionMode by setting type as follows:
```
executionMode {
  type = PartitionDiffMode
  attribute1 = ...
}
```

### Fixed partition values filter
You can apply a filter manually by specifying parameter --partition-values or --multi-partition-values on the command line. The partition values specified are passed to all start-Actions of a DAG and filtered for every input DataObject by its defined partition columns.
On execution every Action takes the partition values of the input and filters them again for every output DataObject by its defined partition columns, which serve again as partition values for the input of the next Action. 
Note that during execution of the dag, no new partition values are added, they are only filtered. An exception is if you place a PartitionDiffMode in the middle of your pipeline, see next section.

### PartitionDiffMode: Dynamic partition values filter 
Alternatively you can let SmartDataLakeBuilder find missing partitions and set partition values automatically by specifying execution mode PartitionDiffMode.

By defining the applyCondition attribute you can give a condition to decide at runtime if the PartitionDiffMode should be applied or not.
Default is to apply the PartitionDiffMode if the given partition values are empty (partition values from command line or passed from previous action). 
Define an applyCondition by a spark sql expression working with attributes of DefaultExecutionModeExpressionData returning a boolean.

By defining the failCondition attribute you can give a condition to fail application of execution mode if true.
It can be used fail a run based on expected partitions, time and so on.
Default is that the application of the PartitionDiffMode does not fail the action. If there is no data to process, the following actions are skipped.
Define a failCondition by a spark sql expression working with attributes of PartitionDiffModeExpressionData returning a boolean.
Example - fail if partitions are not processed in strictly increasing order of partition column "dt":
```
  failCondition = "(size(selectedPartitionValues) > 0 and array_min(transform(selectedPartitionValues, x -> x.dt)) < array_max(transform(outputPartitionValues, x -> x.dt)))"
```

### SparkStreamingOnceMode: Incremental load 
Some DataObjects are not partitioned, but nevertheless you dont want to read all data from the input on every run. You want to load it incrementally.
This can be accomplished by specifying execution mode SparkStreamingOnceMode. Under the hood it uses "Spark Structured Streaming" and triggers a single microbatch (Trigger.Once).
"Spark Structured Streaming" helps keeping state information about processed data. It needs a checkpointLocation configured which can be given as parameter to SparkStreamingOnceMode.

Note that "Spark Structured Streaming" needs an input DataObject supporting the creation of streaming DataFrames. 
For the time being, only the input sources delivered with Spark Streaming are supported. 
This are KafkaTopicDataObject and all SparkFileDataObjects, see also [Spark StructuredStreaming](https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html#creating-streaming-dataframes-and-streaming-datasets).

### SparkIncrementalMode: Incremental Load
As not every input DataObject supports the creation of streaming DataFrames, there is an other execution mode called SparkIncrementalMode.
You configure it by defining the attribute "compareCol" with a column name present in input and output DataObject. 
SparkIncrementalMode then compares the maximum values between input and output and creates a filter condition.
On execution the filter condition is applied to the input DataObject to load the missing increment.
Note that compareCol needs to have a sortable datatype.

By defining the applyCondition attribute you can give a condition to decide at runtime if the SparkIncrementalMode should be applied or not.
Default is to apply the SparkIncrementalMode. Define an applyCondition by a spark sql expression working with attributes of DefaultExecutionModeExpressionData returning a boolean.

### FailIfNoPartitionValuesMode
To simply check if partition values are present and fail otherwise, configure execution mode FailIfNoPartitionValuesMode.
This is useful to prevent potential reprocessing of whole table through wrong usage.

## Metrics
Metrics are gathered per Action and output-DataObject when running a DAG. They can be found in log statements and are written to the state file.

Sample log message:
`2020-07-21 11:36:34 INFO  CopyAction:105 - (Action~a) finished writing DataFrame to DataObject~tgt1: duration=PT0.906S records_written=1 bytes_written=1142 num_tasks=1 stage=save`

A fail condition can be specified on Actions to fail execution if a certain condition is not met.
The condition must be specified as spark sql expression, which is evaluated as where-clause against a dataframe of metrics. Available columns are dataObjectId, key, value. 
To fail above sample log in case there are no records written, specify `"dataObjectId = 'tgt1' and key = 'records_written' and value = 0"`.

By implementing interface StateListener  you can get notified about action results & metrics. To configure state listeners set config attribute `global.stateListeners = [{className = ...}]`.

## Custom Spark Transformations

### Scala
TODO

### SQL
TODO

### Python
It's possible to use Python to define a custom Spark transformation. Input is a PySpark DataFrame and the transformation must return again a PySpark DataFrame.

Requirements: Python version >= 3.4 an <= 3.7 (for Spark 2.4), PySpark package.
See Readme of [sdl-examples](https://github.com/smart-data-lake/sdl-examples) for a working example and instructions to setup environment for IntelliJ  

How it works: under the hood a PySpark DataFrame is a proxy for a Java Spark DataFrame. PySpark uses Py4j to access Java objects in the JVM.

  