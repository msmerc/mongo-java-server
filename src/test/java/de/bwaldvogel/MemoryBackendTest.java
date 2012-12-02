package de.bwaldvogel;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;

import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.HeapChannelBufferFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoException;

import de.bwaldvogel.mongo.backend.MongoServerBackend;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import de.bwaldvogel.mongo.wire.MongoDatabaseHandler;
import de.bwaldvogel.mongo.wire.MongoWireEncoder;
import de.bwaldvogel.mongo.wire.MongoWireProtocolHandler;
import de.bwaldvogel.mongo.wire.message.MongoServer;

public class MemoryBackendTest {
    private static final int PORT = 37017;
    private ChannelFactory factory;
    private Channel serverChannel;
    private Mongo mongo;

    @Before
    public void setUp() throws Exception{
        MongoServerBackend backend = new MemoryBackend();
        final MongoServer mongoServer = new MongoServer( backend );

        factory = new NioServerSocketChannelFactory( Executors.newCachedThreadPool() , Executors.newCachedThreadPool() );
        final ServerBootstrap bootstrap = new ServerBootstrap( factory );
        bootstrap.setOption( "child.bufferFactory", new HeapChannelBufferFactory( ByteOrder.LITTLE_ENDIAN ) );

        // Set up the pipeline factory.
        bootstrap.setPipelineFactory( new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception{
                return Channels.pipeline( new MongoWireEncoder(), new MongoWireProtocolHandler(), new MongoDatabaseHandler( mongoServer ) );
            }
        } );

        serverChannel = bootstrap.bind( new InetSocketAddress( PORT ) );
        mongo = new MongoClient( "localhost" , PORT );
    }

    @After
    public void tearDown(){
        mongo.close();
        serverChannel.close().awaitUninterruptibly();
        factory.releaseExternalResources();
    }

    @Test
    public void testMaxBsonSize() throws Exception{
        int maxBsonObjectSize = mongo.getMaxBsonObjectSize();
        assertThat( maxBsonObjectSize ).isEqualTo( 16777216 );
    }

    @Test
    public void testListDatabaseNames() throws Exception{
        assertThat( mongo.getDatabaseNames() ).isEmpty();
        mongo.getDB( "testdb" ).getCollection( "testcollection" ).insert( new BasicDBObject() );
        assertThat( mongo.getDatabaseNames() ).containsExactly( "testdb" );
        mongo.getDB( "bar" ).getCollection( "testcollection" ).insert( new BasicDBObject() );
        assertThat( mongo.getDatabaseNames() ).containsExactly( "bar", "testdb" );
    }

    @Test
    public void testIllegalCommand() throws Exception{
        try {
            mongo.getDB( "testdb" ).command( "foo" ).throwOnError();
            fail( "MongoException expected" );
        }
        catch ( MongoException e ) {
            assertThat( e.getMessage() ).contains( "no such cmd" );
        }

        try {
            mongo.getDB( "bar" ).command( "foo" ).throwOnError();
            fail( "MongoException expected" );
        }
        catch ( MongoException e ) {
            assertThat( e.getMessage() ).contains( "no such cmd" );
        }
    }

    @Test
    public void testQuery() throws Exception{
        DBCollection collection = mongo.getDB( "testdb" ).getCollection( "testcollection" );
        DBObject obj = collection.findOne( new BasicDBObject( "_id" , 1 ) );
        assertThat( obj ).isNull();
        assertThat( collection.count() ).isEqualTo( 0 );
    }

    @Test
    public void testQueryAll() throws Exception{
        DBCollection collection = mongo.getDB( "testdb" ).getCollection( "testcollection" );

        List<Object> inserted = new ArrayList<Object>();
        for ( int i = 0; i < 10; i++ ) {
            BasicDBObject obj = new BasicDBObject( "_id" , i );
            collection.insert( obj );
            inserted.add( obj );
        }
        assertThat( collection.count() ).isEqualTo( 10 );

        assertThat( collection.find().toArray() ).isEqualTo( inserted );
    }

    @Test
    public void testInsert() throws Exception{
        DBCollection collection = mongo.getDB( "testdb" ).getCollection( "testcollection" );
        assertThat( collection.count() ).isEqualTo( 0 );

        for ( int i = 0; i < 3; i++ ) {
            collection.insert( new BasicDBObject( "_id" , Integer.valueOf( i ) ) );
        }

        assertThat( collection.count() ).isEqualTo( 3 );
    }

    @Test
    public void testInsertDuplicate() throws Exception{
        DBCollection collection = mongo.getDB( "testdb" ).getCollection( "testcollection" );
        assertThat( collection.count() ).isEqualTo( 0 );

        collection.insert( new BasicDBObject( "_id" , 1 ) );
        assertThat( collection.count() ).isEqualTo( 1 );

        try {
            collection.insert( new BasicDBObject( "_id" , 1 ) );
            fail( "MongoException expected" );
        }
        catch ( MongoException e ) {
            assertThat( e.getMessage() ).contains( "duplicate key error" );
        }

        try {
            collection.insert( new BasicDBObject( "_id" , 1.0 ) );
            fail( "MongoException expected" );
        }
        catch ( MongoException e ) {
            assertThat( e.getMessage() ).contains( "duplicate key error" );
        }

        assertThat( collection.count() ).isEqualTo( 1 );
    }

    @Test
    public void testInsertQuery() throws Exception{
        DBCollection collection = mongo.getDB( "testdb" ).getCollection( "testcollection" );
        assertThat( collection.count() ).isEqualTo( 0 );

        BasicDBObject insertedObject = new BasicDBObject( "_id" , 1 );
        insertedObject.put( "foo", "bar" );

        collection.insert( insertedObject );

        assertThat( collection.findOne( insertedObject ) ).isEqualTo( insertedObject );
        assertThat( collection.findOne( new BasicDBObject( "_id" , 1l ) ) ).isEqualTo( insertedObject );
        assertThat( collection.findOne( new BasicDBObject( "_id" , 1.0 ) ) ).isEqualTo( insertedObject );
        assertThat( collection.findOne( new BasicDBObject( "_id" , 1.0001 ) ) ).isNull();
        assertThat( collection.findOne( new BasicDBObject( "foo" , "bar" ) ) ).isEqualTo( insertedObject );
        assertThat( collection.findOne( new BasicDBObject( "foo" , null ) ) ).isEqualTo( insertedObject );
    }

    @Test
    public void testInsertRemove() throws Exception{
        DBCollection collection = mongo.getDB( "testdb" ).getCollection( "testcollection" );

        for ( int i = 0; i < 10; i++ ) {
            collection.insert( new BasicDBObject( "_id" , 1 ) );
            collection.remove( new BasicDBObject( "_id" , 1 ) );
            collection.insert( new BasicDBObject( "_id" , i ) );
            collection.remove( new BasicDBObject( "_id" , i ) );
        }
        collection.remove( new BasicDBObject( "doesnt exist" , 1 ) );
        assertThat( collection.count() ).isEqualTo( 0 );
    }

    @Test
    public void testUpdate() throws Exception{
        DBCollection collection = mongo.getDB( "testdb" ).getCollection( "testcollection" );

        BasicDBObject object = new BasicDBObject( "_id" , 1 );

        BasicDBObject newObject = new BasicDBObject( "_id" , 1 );
        newObject.put( "foo", "bar" );

        collection.insert( object );
        collection.update( object, newObject );
        assertThat( collection.findOne( object ) ).isEqualTo( newObject );
    }

    @Test
    public void testUpsert() throws Exception{
        DBCollection collection = mongo.getDB( "testdb" ).getCollection( "testcollection" );

        BasicDBObject object = new BasicDBObject( "_id" , 1 );

        BasicDBObject newObject = new BasicDBObject( "_id" , 1 );
        newObject.put( "foo", "bar" );

        collection.update( object, newObject, true, false );
        assertThat( collection.findOne( object ) ).isEqualTo( newObject );
    }

    @Test
    public void testDropDatabase() throws Exception{
        mongo.getDB( "testdb" ).getCollection( "foo" ).insert( new BasicDBObject() );
        assertThat( mongo.getDatabaseNames() ).containsExactly( "testdb" );
        mongo.dropDatabase( "testdb" );
        assertThat( mongo.getDatabaseNames() ).isEmpty();
    }

    @Test
    public void testDropCollection() throws Exception{
        DB db = mongo.getDB( "testdb" );
        db.getCollection( "foo" ).insert( new BasicDBObject() );
        assertThat( db.getCollectionNames() ).containsOnly( "foo" );
        db.getCollection( "foo" ).drop();
        assertThat( db.getCollectionNames() ).isEmpty();
    }

    @Test
    public void testReplicaSetInfo() throws Exception{
        // ReplicaSetStatus status = mongo.getReplicaSetStatus();
        // System.out.println(status);
        // assertThat(status)
    }

}