# Maven S3 Wagon

A Maven Wagon to connect to Amazon Web Services S3 based repository (AWS S3 WAGON)

## Requirements and Dependencies

To run this wagon you will need Java **1.8+**

This project also depends on Amazon Web Services SDK for Java version 2.
More details on that can be found at [AWS SDK for Java 2.0 GitHub Page](https://github.com/aws/aws-sdk-java-v2)

## Maven Coordinates

```xml
	<groupId>io.trustep.maven</groupId>
	<artifactId>maven-s3-wagon</artifactId>
	<version>0.1.1</version>
```

## Configuration

The S3 Wagon Configuration involves two main components: configuring your AWS services and configure Maven. The configurations required in AWS involves basically two Services: IAM and S3. In Maven you will have to touch three files:  the settings.xml file; the pom.xml project file; and the .mvn/extensions.xml project file.

## AWS Configuration

The objective here is to create a S3 private bucket that will serve us as a private Maven Repository.
The steps for in doing that involves touching two services: IAM and S3 itself. We assume you are familiar with AWS services we mention here (IAM and S3) and you also have access to a valid AWS account and/or an AWS IAM User with appropriated privileges to execute operations indicated in this documentation.

After those configurations, you will have a S3 private bucket ready to be used as a Maven Repository.
It will work as a simple artifact repository and have no sophisticated features by default. You can allways use other AWS services, like Lambda and/or Cloudwatch to implement your desired features, for example, prune snapshot directories when appropriated versions are released, but this is out of scope of this instructions.

Here is the step by step instructions:

1.  **Create a S3 Bucket**

    First thing you need to do is to create a S3 bucket to be your repository. I will not give details on that since it is very straightforward. Take note on the name you choose for your bucket and the AWS region you created it. Also take care to keep your bucket private, which at this time is the default option. For example, you can create a bucket named "**repo.yourcompany.com**"

1.  **Create a Policy in IAM**
    
    First of all you will need to create a policy that allows necessary operations on the bucket. You can attach the necessary permissions to users directly, but it is not the recommended way to handle AWS security. So, we will recommend you create a policy like this:

    ```json
        {
	        "Version": "2012-10-17",
	        "Statement": [
	            {
	                "Sid": "S3WagonPrivateAllowConfiguration",
	                "Effect": "Allow",
	                "Action": [
	                    "s3:ListBucket",
	                    "s3:GetBucketLocation"
	                ],
	                "Resource": [
	                    "arn:aws:s3:::repo.yourcompany.com"
	                ]
	            },
	            {
	                "Sid": "S3WagonPrivateAllowGetAndPut",
	                "Effect": "Allow",
	                "Action": [
	                    "s3:GetObject",
	                    "s3:GetObjectVersion",
	                    "s3:ListBucket",
	                    "s3:PutObject"
	                ],
	                "Resource": [
	                    "arn:aws:s3:::repo.yourcompany.com/*"
	                ]
	            }
	        ]
        }
    ```
    
    You should change all occurrences of "repo.yourcompany.com" with your bucket name.
    You also can name your policy anything you want, for example repo-yourcompany-com-policy
    
1.  **Create a Group in IAM**

    AWS recommends as a security best practice to never attach permissions and/or policies directly into your users. They recommends to create groups and then assign Users to respective groups.
    
    So we recommend you follow AWS best practices and create a group to hold all users that will have access to the repository. You should attach the policy created in step one to this group. You also will need to attach all users that will have access to the repository to the group as well.
    
1.  **Create Users in IAM**

    If you don't have created any users yet, now is the time to create them. We are referencing as "user" an IAM user, not an AWS account user. It is not necessary that users are created with console access, although it will not prevent them to access the repository if they have it. As long as those user have API access, the configuration shown here will works fine.

1.  **Associate each user to the group in IAM**
    
    As mentioned in step two, you will need to associate each user to the group created in that step. You can select the group created in step two and attach all users or you can select each user and add the group to him/her.

1.  **Create Access Keys for each user in IAM**
    
    To allow authentication through AWS SDK, you will need to create an Access Key for each user. To achieve this you select the desired user, then selects the tab "Security Credentials", and then press the button "Create Access Key". The console will show you the Access Key ID and Secret Access Key values. Take note on those or download the file with those values. If the values are lost, the key should be invalidated and a new key created.
    
    If the user already have an access key created, that key can be used as long as the user knows Access Key ID and Secret Access Key values. Also, any other method to create and access key can be used, as long as the user can have access to Access Key ID and Secret Access Key values. 

    The **Access Key ID** and **Secret Access Key** values will be needed to configure Maven in the sequence.
    

## Maven Configuration

After you have created all AWS infrastructure needed now it's time to configure maven to use the S3 bucket as a private repository.

Here are the step by step guide:

1.  **Configure your settings.xml file**

    The settings.xml file is a configuration file that configures maven executions in various ways. There are two settings.xml files that can be found in maven, accordingly to [Maven Settings Reference](http://maven.apache.org/settings.html). You should use the "user level" settings.xml file, which resides tipically in ~/.m2/settings.xml path. If it is not there you can create a new one. It is intended to be a per-user configuration file, independent of any particular project, so it is not bundled with any particular project.
    Assuming that it already exists, the first thing you should do is to add a <server> tag to settings.xml similar to this:

    ```xml
        <servers>
		    <server>
			    <id>MY_REPOSITORY_ID</id>
			    <username>MY_ACCESS_KEY_ID</username>
			    <password>{MY_ENCRIPTED_SECRET_ACCESS_KEY}</password>
			    <configuration>
				    <region>us-east-1</region> <!-- points to the region you created the S3 bucket before -->
			    </configuration>
		    </server>
	    </servers>
    ```

    Here is were you tell Maven engine which credentials to use to connect to a given repository Id. Later in the pom.xml project file we will associate this repository Id with the S3 direction of your bucket. The **Access Key ID** created before is informed in plain text. In the password tag you will put an encrypted form of your **Secret Access Key**. To encrypt you Secret Access Key you should follow instructions found at **[Password Encryption](http://maven.apache.org/guides/mini/guide-encryption.html)** Maven Guide.
    
    The credentials one informs in settings.xml takes precedence over any other form of retrieving credentials used by the AWS SDK for Java 2.0. If you omit username, password and region here, the S3 Wagon will delegate to the AWS SDK Client the responsibility of retrieving credentials. More information on how AWS SDK for Java 2.0 retrieve credentials can be found [here](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/credentials.html). 

1.  **Configure your pom.xml**

    Now you can associate your project with the server you created in settings.xml. Open your pom.xml and adds the following lines (or adjust if they already exists):
    
    ```xml
        <distributionManagement>
		    <snapshotRepository>
			    <id>MY_REPOSITORY_ID</id> <!-- must match repository Id informed in settings.xml file -->
			    <url>s3://repo.yourcompany.com/snapshot</url> <!-- must match s3 bucket name created before -->
		    </snapshotRepository>
		    <repository>
			    <id>MY_REPOSITORY_ID</id> <!-- must match repository Id informed in settings.xml file -->
			    <url>s3://repo.yourcompany.com/release</url> <!-- must match s3 bucket name created before -->
		    </repository>
	    </distributionManagement>
	    <repositories>
		    <repository>
			    <id>MY_REPOSITORY_ID</id> <!-- must match repository Id informed in settings.xml file -->
			    <name>Trustep Private Maven Repository</name> 
			    <url>s3://repo.yourcompany.com/release</url> <!-- must match s3 bucket name created before -->
		    </repository>
	    </repositories>
    ```
    
1.  **Configure Maven S3 Wagon**

    To associate the Maven S3 Wagon to your project you need to create an extensions.xml file within a directory .mvn created in the same folder that your pom.xml resides. The .mvn/extensions.xml should be similar to this:
    
    ```xml
        <extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
            <extension>
                <groupId>io.trustep.maven</groupId>
                <artifactId>maven-s3-wagon</artifactId>
                <version>0.1.1</version>
            </extension>
        </extensions>
    ```
    
    
You are now ready to deploy your Maven artifacts to your recently created AWS S3 bucket. You can try mvn clean package deploy and check your S3 bucket to view your artifacts recently deployed. You should look to release or snapshot folder accordingly to your version type deployed.
    
## Warning

This is a still under development version. There are not unit or integration tests written.
The only tests made manually was using a private S3 repository. So this version should be considered a simple working a proof of concept. 

In particular, the checksum calculations throws warning messages, nevertheless the resulting checksum files in the repository are correctly generated.
