This test demonstrates a problem when uploading several files in
parallel in different threads.

The problem is very similar to the one described in
[3073](https://github.com/Azure/azure-sdk-for-java/issues/3073)
Basically, an exception with the following message is thrown:

**The uploaded data is not contiguous or the position query parameter
value is not equal to the length of the file after appending the
uploaded data**

The file `run.log` contains the stack trace when the program terminates.
The suggestion to use `createDirectoryIfNotExists()` instead of
`createDirectory()` does not help.

Steps needed to reproduce the problem:

1.  Build the jar file by typing:

        mvn clean package

2.  Run the program by typing:

        java -jar target/test-azure-0.1.jar <accountName> <accessToken> <numFiles> <localDirName> <remoteDirName>
