/*
 *    Copyright 2020 - Trustep Servicos de Informatica Ltda
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package io.trustep.maven.wagon.providers.s3;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.SessionEvent;
import org.apache.maven.wagon.events.SessionEventSupport;
import org.apache.maven.wagon.events.SessionListener;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferEventSupport;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.proxy.ProxyInfoProvider;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.repository.RepositoryPermissions;
import org.apache.maven.wagon.resource.Resource;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Wagon Provider for AWS S3 service
 *
 * @author <a href="gilcesarf@trustpe.io">Gil Cesar Faria</a>
 * @plexus.component role="org.apache.maven.wagon.Wagon" role-hint="s3" instantiation-strategy="per-lookup"
 */
public class S3Wagon
    implements Wagon
{
    protected static final int DEFAULT_BUFFER_SIZE = 4 * 1024;

    protected static final int MAXIMUM_BUFFER_SIZE = 512 * 1024;

    /**
     * To efficiently buffer data, use a multiple of 4 KiB as this is likely to match the hardware buffer size of
     * certain storage devices.
     */
    protected static final int BUFFER_SEGMENT_SIZE = 4 * 1024;

    /**
     * The desired minimum amount of chunks in which a {@link Resource} shall be
     * {@link #transfer(Resource, InputStream, OutputStream, int, long) transferred}. This corresponds to the minimum
     * times {@link #fireTransferProgress(TransferEvent, byte[], int)} is executed. 100 notifications is a conservative
     * value that will lead to small chunks for any artifact less that {@link #BUFFER_SEGMENT_SIZE} *
     * {@link #MINIMUM_AMOUNT_OF_TRANSFER_CHUNKS} in size.
     */
    protected static final int MINIMUM_AMOUNT_OF_TRANSFER_CHUNKS = 100;

    protected Repository repository;

    protected SessionEventSupport sessionEventSupport = new SessionEventSupport();

    protected TransferEventSupport transferEventSupport = new TransferEventSupport();

    protected AuthenticationInfo authenticationInfo;

    protected boolean interactive = true;

    private int connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;

    private String region = null;

    private S3Client s3Client = null;

    /**
     * read timeout value
     *
     * @since 2.2
     */
    private int readTimeout =
        Integer.parseInt( System.getProperty( "maven.wagon.rto", Integer.toString( Wagon.DEFAULT_READ_TIMEOUT ) ) );

    private ProxyInfoProvider proxyInfoProvider;

    private RepositoryPermissions permissionsOverride;

    @Override
    public void get( String resourceName, File destination )
        throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        checkBaseDir();
        String baseDir = getRepository().getBasedir().replaceAll( "/", "" );
        String bucket = getRepository().getHost();
        String key = baseDir + "/" + resourceName;
        Resource resource = new Resource( resourceName );
        fireGetInitiated( resource, destination );

        File tmp = new File( destination.getAbsolutePath() + ".tmp" );
        try
        {
            GetObjectRequest req = GetObjectRequest.builder().bucket( bucket ).key( key ).build();
            fireGetStarted( resource, destination );
            GetObjectResponse res = s3Client.getObject( req, ResponseTransformer.toFile( tmp.toPath() ) );
            if ( destination.exists() )
            {
                destination.delete();
            }
            Files.move( tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING );
        }
        catch ( NoSuchKeyException e )
        {
        }
        catch ( Exception e )
        {
            e.printStackTrace();
        }
        finally
        {
            fireGetCompleted( resource, destination );
        }
        if ( tmp != null )
        {
            tmp.delete();
        }
    }

    @Override
    public boolean getIfNewer( String resourceName, File destination, long timestamp )
        throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        // TODO Auto-generated method stub
        System.out.println( "S3Wagon.getIfNewer( resourceName = " + resourceName + ", destination=" + destination
            + ", timestamp = " + timestamp + ")" );
        return false;
    }

    @Override
    public void put( File source, String destination )
        throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        checkBaseDir();
        Resource resource = new Resource( destination );

        firePutInitiated( resource, source );
        resource.setContentLength( source.length() );
        resource.setLastModified( source.lastModified() );
        firePutInitiated( resource, source );

        String baseDir = getRepository().getBasedir().replaceAll( "/", "" );
        String bucket = getRepository().getHost();
        String key = baseDir + "/" + destination;
        try
        {
            firePutStarted( resource, source );
            s3Client.putObject( PutObjectRequest.builder().bucket( bucket ).key( key ).build(),
                                RequestBody.fromFile( source ) );
        }
        finally
        {
            firePutCompleted( resource, source );
        }

    }

    @Override
    public void putDirectory( File sourceDirectory, String destDir )
        throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        throw new UnsupportedOperationException( "The wagon you are using has not implemented putDirectory()" );
    }

    @Override
    public boolean resourceExists( String resourceName )
        throws TransferFailedException, AuthorizationException
    {
        throw new UnsupportedOperationException( "The wagon you are using has not implemented resourceExists()" );
    }

    @Override
    public List<String> getFileList( String destinationDirectory )
        throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        if ( getRepository().getBasedir() == null )
        {
            throw new TransferFailedException( "Unable to getFileList() with a null basedir." );
        }

        File path = resolveDestinationPath( destinationDirectory );

        if ( !path.exists() )
        {
            throw new ResourceDoesNotExistException( "Directory does not exist: " + destinationDirectory );
        }

        if ( !path.isDirectory() )
        {
            throw new ResourceDoesNotExistException( "Path is not a directory: " + destinationDirectory );
        }

        File[] files = path.listFiles();

        List<String> list = new ArrayList<String>( files.length );
        for ( File file : files )
        {
            String name = file.getName();
            if ( file.isDirectory() && !name.endsWith( "/" ) )
            {
                name += "/";
            }
            list.add( name );
        }
        return list;
    }

    @Override
    public boolean supportsDirectoryCopy()
    {
        return false;
    }

    @Override
    public void connect( Repository repository )
        throws ConnectionException, AuthenticationException
    {
        connect( repository, null, (ProxyInfoProvider) null );
    }

    @Override
    public void connect( Repository repository, ProxyInfo proxyInfo )
        throws ConnectionException, AuthenticationException
    {
        connect( repository, null, proxyInfo );
    }

    @Override
    public void connect( Repository repository, ProxyInfoProvider proxyInfoProvider )
        throws ConnectionException, AuthenticationException
    {
        connect( repository, null, proxyInfoProvider );
    }

    @Override
    public void connect( Repository repository, AuthenticationInfo authenticationInfo )
        throws ConnectionException, AuthenticationException
    {
        connect( repository, authenticationInfo, (ProxyInfoProvider) null );
    }

    @Override
    public void connect( Repository repository, AuthenticationInfo authenticationInfo, ProxyInfo proxyInfo )
        throws ConnectionException, AuthenticationException
    {
        final ProxyInfo proxy = proxyInfo;
        connect( repository, authenticationInfo, new ProxyInfoProvider()
        {
            public ProxyInfo getProxyInfo( String protocol )
            {
                if ( protocol == null || proxy == null || protocol.equalsIgnoreCase( proxy.getType() ) )
                {
                    return proxy;
                }
                else
                {
                    return null;
                }
            }
        } );
    }

    @Override
    public void connect( Repository repository, AuthenticationInfo authenticationInfo,
                         ProxyInfoProvider proxyInfoProvider )
        throws ConnectionException, AuthenticationException
    {
        if ( repository == null )
        {
            throw new NullPointerException( "repository cannot be null" );
        }

        if ( permissionsOverride != null )
        {
            repository.setPermissions( permissionsOverride );
        }

        this.repository = repository;

        if ( authenticationInfo == null )
        {
            authenticationInfo = new AuthenticationInfo();
        }

        if ( authenticationInfo.getUserName() == null )
        {
            // Get user/pass that were encoded in the URL.
            if ( repository.getUsername() != null )
            {
                authenticationInfo.setUserName( repository.getUsername() );
                if ( repository.getPassword() != null && authenticationInfo.getPassword() == null )
                {
                    authenticationInfo.setPassword( repository.getPassword() );
                }
            }
        }

        this.authenticationInfo = authenticationInfo;

        this.proxyInfoProvider = proxyInfoProvider;

        fireSessionOpening();

        openConnection();

        fireSessionOpened();
    }

    @Override
    public void openConnection()
        throws ConnectionException, AuthenticationException
    {
        AwsCredentialsProvider credentialsProvider = null;
        AwsSessionCredentials awsCredentials = null;
        if ( s3Client != null )
        {
            s3Client.close();
            s3Client = null;
        }
        if ( authenticationInfo.getUserName() != null && !"".equals( authenticationInfo.getUserName() ) )
        {
            awsCredentials =
                AwsSessionCredentials.create( authenticationInfo.getUserName(), authenticationInfo.getPassword(), "" );
        }
        if ( awsCredentials != null )
        {
            // if given by authenticationInfo, use credentials from it
            credentialsProvider = StaticCredentialsProvider.create( awsCredentials );
        }
        else
        {
            // otherwise uses DefaultCredentialsProvider
            credentialsProvider = DefaultCredentialsProvider.create();
        }
        S3ClientBuilder builder = S3Client.builder().credentialsProvider( credentialsProvider );
        if ( this.region == null )
        {
            builder.region( Region.of( this.region.toLowerCase() ) );
        }
        s3Client = builder.build();
    }

    @Override
    public void disconnect()
        throws ConnectionException
    {
        fireSessionDisconnecting();

        try
        {
            closeConnection();
        }
        catch ( ConnectionException e )
        {
            fireSessionError( e );
            throw e;
        }

        fireSessionDisconnected();
    }

    protected void closeConnection()
        throws ConnectionException
    {
        if ( s3Client != null )
        {
            s3Client.close();
            s3Client = null;
        }
    };

    //
    // transferEventSupport related methods
    //

    @Override
    public boolean hasTransferListener( TransferListener listener )
    {
        return transferEventSupport.hasTransferListener( listener );
    }

    @Override
    public void addTransferListener( TransferListener listener )
    {
        transferEventSupport.addTransferListener( listener );
    }

    @Override
    public void removeTransferListener( TransferListener listener )
    {
        transferEventSupport.removeTransferListener( listener );
    }

    protected void fireTransferProgress( TransferEvent transferEvent, byte[] buffer, int n )
    {
        transferEventSupport.fireTransferProgress( transferEvent, buffer, n );
    }

    protected void fireGetCompleted( Resource resource, File localFile )
    {
        long timestamp = System.currentTimeMillis();
        TransferEvent transferEvent =
            new TransferEvent( this, resource, TransferEvent.TRANSFER_COMPLETED, TransferEvent.REQUEST_GET );
        transferEvent.setTimestamp( timestamp );
        transferEvent.setLocalFile( localFile );
        transferEventSupport.fireTransferCompleted( transferEvent );
    }

    protected void fireGetStarted( Resource resource, File localFile )
    {
        long timestamp = System.currentTimeMillis();
        TransferEvent transferEvent =
            new TransferEvent( this, resource, TransferEvent.TRANSFER_STARTED, TransferEvent.REQUEST_GET );
        transferEvent.setTimestamp( timestamp );
        transferEvent.setLocalFile( localFile );
        transferEventSupport.fireTransferStarted( transferEvent );
    }

    protected void fireGetInitiated( Resource resource, File localFile )
    {
        long timestamp = System.currentTimeMillis();
        TransferEvent transferEvent =
            new TransferEvent( this, resource, TransferEvent.TRANSFER_INITIATED, TransferEvent.REQUEST_GET );
        transferEvent.setTimestamp( timestamp );
        transferEvent.setLocalFile( localFile );
        transferEventSupport.fireTransferInitiated( transferEvent );
    }

    protected void firePutInitiated( Resource resource, File localFile )
    {
        long timestamp = System.currentTimeMillis();
        TransferEvent transferEvent =
            new TransferEvent( this, resource, TransferEvent.TRANSFER_INITIATED, TransferEvent.REQUEST_PUT );
        transferEvent.setTimestamp( timestamp );
        transferEvent.setLocalFile( localFile );
        transferEventSupport.fireTransferInitiated( transferEvent );
    }

    protected void firePutCompleted( Resource resource, File localFile )
    {
        long timestamp = System.currentTimeMillis();
        TransferEvent transferEvent =
            new TransferEvent( this, resource, TransferEvent.TRANSFER_COMPLETED, TransferEvent.REQUEST_PUT );
        transferEvent.setTimestamp( timestamp );
        transferEvent.setLocalFile( localFile );
        transferEventSupport.fireTransferCompleted( transferEvent );
    }

    protected void firePutStarted( Resource resource, File localFile )
    {
        long timestamp = System.currentTimeMillis();
        TransferEvent transferEvent =
            new TransferEvent( this, resource, TransferEvent.TRANSFER_STARTED, TransferEvent.REQUEST_PUT );
        transferEvent.setTimestamp( timestamp );
        transferEvent.setLocalFile( localFile );
        transferEventSupport.fireTransferStarted( transferEvent );
    }

    //
    // sessionEventSupport related methods
    //
    @Override
    public void addSessionListener( SessionListener listener )
    {
        sessionEventSupport.addSessionListener( listener );
    }

    @Override
    public boolean hasSessionListener( SessionListener listener )
    {
        return sessionEventSupport.hasSessionListener( listener );
    }

    @Override
    public void removeSessionListener( SessionListener listener )
    {
        sessionEventSupport.removeSessionListener( listener );
    }

    protected void fireSessionDisconnected()
    {
        long timestamp = System.currentTimeMillis();
        SessionEvent sessionEvent = new SessionEvent( this, SessionEvent.SESSION_DISCONNECTED );
        sessionEvent.setTimestamp( timestamp );
        sessionEventSupport.fireSessionDisconnected( sessionEvent );
    }

    protected void fireSessionDisconnecting()
    {
        long timestamp = System.currentTimeMillis();
        SessionEvent sessionEvent = new SessionEvent( this, SessionEvent.SESSION_DISCONNECTING );
        sessionEvent.setTimestamp( timestamp );
        sessionEventSupport.fireSessionDisconnecting( sessionEvent );
    }

    protected void fireSessionLoggedIn()
    {
        long timestamp = System.currentTimeMillis();
        SessionEvent sessionEvent = new SessionEvent( this, SessionEvent.SESSION_LOGGED_IN );
        sessionEvent.setTimestamp( timestamp );
        sessionEventSupport.fireSessionLoggedIn( sessionEvent );
    }

    protected void fireSessionLoggedOff()
    {
        long timestamp = System.currentTimeMillis();
        SessionEvent sessionEvent = new SessionEvent( this, SessionEvent.SESSION_LOGGED_OFF );
        sessionEvent.setTimestamp( timestamp );
        sessionEventSupport.fireSessionLoggedOff( sessionEvent );
    }

    protected void fireSessionOpened()
    {
        long timestamp = System.currentTimeMillis();
        SessionEvent sessionEvent = new SessionEvent( this, SessionEvent.SESSION_OPENED );
        sessionEvent.setTimestamp( timestamp );
        sessionEventSupport.fireSessionOpened( sessionEvent );
    }

    protected void fireSessionOpening()
    {
        long timestamp = System.currentTimeMillis();
        SessionEvent sessionEvent = new SessionEvent( this, SessionEvent.SESSION_OPENING );
        sessionEvent.setTimestamp( timestamp );
        sessionEventSupport.fireSessionOpening( sessionEvent );
    }

    protected void fireSessionConnectionRefused()
    {
        long timestamp = System.currentTimeMillis();
        SessionEvent sessionEvent = new SessionEvent( this, SessionEvent.SESSION_CONNECTION_REFUSED );
        sessionEvent.setTimestamp( timestamp );
        sessionEventSupport.fireSessionConnectionRefused( sessionEvent );
    }

    protected void fireSessionError( Exception exception )
    {
        long timestamp = System.currentTimeMillis();
        SessionEvent sessionEvent = new SessionEvent( this, exception );
        sessionEvent.setTimestamp( timestamp );
        sessionEventSupport.fireSessionError( sessionEvent );
    }

    protected void fireTransferDebug( String message )
    {
        transferEventSupport.fireDebug( message );
    }

    protected void fireSessionDebug( String message )
    {
        sessionEventSupport.fireDebug( message );
    }

    //
    // Getters and Setters
    //

    @Override
    public Repository getRepository()
    {
        return repository;
    }

    @Override
    public void setTimeout( int timeoutValue )
    {
        connectionTimeout = timeoutValue;
    }

    @Override
    public int getTimeout()
    {
        return connectionTimeout;
    }

    @Override
    public void setReadTimeout( int readTimeout )
    {
        this.readTimeout = readTimeout;
    }

    @Override
    public int getReadTimeout()
    {
        return this.readTimeout;
    }

    @Override
    public boolean isInteractive()
    {
        return interactive;
    }

    @Override
    public void setInteractive( boolean interactive )
    {
        this.interactive = interactive;
    }

    // TODO: Evaluar si debe permanecer
    public RepositoryPermissions getPermissionsOverride()
    {
        return permissionsOverride;
    }

    public void setPermissionsOverride( RepositoryPermissions permissionsOverride )
    {
        this.permissionsOverride = permissionsOverride;
    }

    public ProxyInfo getProxyInfo()
    {
        return proxyInfoProvider != null ? proxyInfoProvider.getProxyInfo( null ) : null;
    }

    public AuthenticationInfo getAuthenticationInfo()
    {
        return authenticationInfo;
    }

    public String getRegion()
    {
        return region;
    }

    public void setRegion( String region )
    {
        this.region = region;
    }

    // internal stuff

    private File resolveDestinationPath( String destinationPath )
    {
        String basedir = getRepository().getBasedir();

        destinationPath = destinationPath.replace( "\\", "/" );

        File path;

        if ( destinationPath.equals( "." ) )
        {
            path = new File( basedir );
        }
        else
        {
            path = new File( basedir, destinationPath );
        }

        return path;
    }

    private void checkBaseDir()
        throws TransferFailedException
    {
        if ( getRepository().getBasedir() == null )
        {
            throw new TransferFailedException( "Unable to operate with a null basedir." );
        }
    }

    private HashMap<String, S3Object> getObjectMap()
    {
        String bucket = getRepository().getHost();
        ListObjectsV2Request req = ListObjectsV2Request.builder().bucket( bucket ).maxKeys( 1 ).build();
        HashMap<String, S3Object> map = new HashMap<>();
        boolean done = false;
        while ( !done )
        {
            ListObjectsV2Response res = s3Client.listObjectsV2( req );
            for ( S3Object content : res.contents() )
            {
                map.put( content.key(), content );
            }
            done = res.nextContinuationToken() == null;
            if ( !done )
            {
                req = req.toBuilder().continuationToken( res.nextContinuationToken() ).build();
            }
        }
        return map;
    }

    private List<File> buildFileList( File sourceDirectory )
    {
        ArrayList<File> ret = new ArrayList<File>();
        File[] files = sourceDirectory.listFiles();
        for ( int i = 0; i < files.length; i++ )
        {
            if ( files[i].isDirectory() )
            {
                ret.addAll( buildFileList( files[i] ) );
            }
            else
            {
                ret.add( files[i] );
            }
        }
        return ret;
    }

    private String stripFirstSlash( String relativeFilename )
    {
        if ( relativeFilename.trim().startsWith( "/" ) )
        {
            return relativeFilename.trim().replaceFirst( "/", "" );
        }
        else
        {
            return relativeFilename.trim();
        }
    }

}
