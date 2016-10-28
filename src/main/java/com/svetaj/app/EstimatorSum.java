
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.processor.TopologyBuilder;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.concurrent.ExecutionException;
import java.util.Locale;
import java.util.Properties;
import java.util.HashSet;
import java.util.Date;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.io.StringReader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

import com.clearspring.analytics.stream.cardinality.LinearCounting;
import com.clearspring.analytics.stream.cardinality.HyperLogLog;

/**
 * SUMMARIZE CARDINALITY ESTIMATORS ALREADY CREATED FOR GIVEN INTERVALS
 * USES SIMPLE BIT ARRAY ADDITION FROM LINEAR COUNTING ALGORITHM
 * SERIALIZE RESULTING ESTIMATOR, CREATE JSON OBJECT AND WRITE TO OUTPUT STREAM
 * SUMMATION OF MULTIPLE TERMS REQUIRES IDENTICAL NUMBER OF BYTES IN EVERY TERM AND RESULT
 * SUMMARIZE ALL RECORDS RECEIVED FROM INPUT STREAM, PRODUCES ONE OR MORE RESULTING RECORDS
 *
 * USAGE: bin/kafka-run-class.sh EstimatorSum in_topic out_topic bytes seconds
 *        java EstimatorSum stdin stdout bytes seconds
 */

public class EstimatorSum {
    
    private static String json_key;
    private static HashSet<String> hs;
    private static HyperLogLog hl;
    private static LinearCounting lc;
    private static boolean hs_flag;
    private static boolean hl_flag;
    private static boolean lc_flag;
    private static int hl_par;
    private static int lc_par;
    private static int bytes;
    private static int seconds;
    private static boolean debug;
 
    private static String byteArrayToHex(byte[] a) {
       StringBuilder sb = new StringBuilder(a.length * 2);
       for(byte b: a)
          sb.append(String.format("%02x", b & 0xff));
       return sb.toString();
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private static void initCounting() {
         hl_par = 10;
         lc_par = bytes;
         if (hs_flag) hs = new HashSet<String>();
         if (hl_flag) hl = new HyperLogLog(hl_par);
         if (lc_flag) lc = new LinearCounting(lc_par);
         debug = true;
    }

    private static void processCounting(String line) {
        try {
            JSONParser parser = new JSONParser();
            Object obj = parser.parse(new StringReader(line));
            JSONObject jsonObject = (JSONObject) obj;
            String est = (String) jsonObject.get("est");
            long ts = (Long) jsonObject.get("ts");
            long range = (Long) jsonObject.get("range");
            long ec = (Long) jsonObject.get("ec");
            byte[] map = hexStringToByteArray(est);
            LinearCounting[] lc_array = new LinearCounting[2];
            lc_array[0] = lc;
            lc_array[1] = new LinearCounting(map);
            if (debug) System.out.println("----------------------");
            if (debug) System.out.println("{term_1:"+byteArrayToHex(lc_array[0].getBytes())+"}");
            if (debug) System.out.println("{term_2:"+byteArrayToHex(lc_array[1].getBytes())+"}");
            lc = LinearCounting.mergeEstimators(lc_array);
            if (debug) System.out.println("{result:"+byteArrayToHex (lc.getBytes())+"}");
            if (debug) System.out.println("----------------------");
        }
        catch (IOException ex) {
            ex.printStackTrace();
        }
        catch (ParseException ex) {
            ex.printStackTrace();
        }
        catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void punctuateCounting(long timestamp) {
        if (debug) System.out.println(new Date(timestamp).toString());
            
    }

    private static void closeCounting() {  }

    private static KafkaStreams initKafkaStream(String src_str, String dst_str) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "my-cat-processor");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, "localhost:2181");
        props.put(StreamsConfig.KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        // setting offset reset to earliest so that we can re-run the demo code with the same pre-loaded data
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("Source", src_str);
        builder.addProcessor("Process", new DataEstimatorProcessorSupplier(), "Source");
        builder.addSink("Destination", dst_str, "Process");

        return new KafkaStreams(builder, props);
    }

    private static class EstimatorSerializer<T> implements Serializer<T> {

        @Override
        public void configure(Map<String, ?> map, boolean b) { }

        @Override
        public byte[] serialize(String topic, T t) {
            return estimatorJSON().getBytes();
        }

        @Override
        public void close() { }
    }

    private static class DataEstimatorProcessorSupplier implements ProcessorSupplier<String, String> {

        @Override
        public Processor<String, String> get() {
            return new Processor<String, String>() {
                private ProcessorContext context;

                @Override
                @SuppressWarnings("unchecked")
                public void init(ProcessorContext context) {
                    this.context = context;
                    //this.context.schedule(seconds*1000);
                    this.context.schedule(60*1000);
                    initCounting();
                }

                @Override
                public void process(String dummy, String line) {
                    processCounting(line);
                }

                @Override
                public void punctuate(long timestamp) {
                    punctuateCounting(timestamp);
                    context.forward("ESTIMATOR", estimatorJSON());
                }

                @Override
                public void close() {
                    closeCounting();
                }
            };
        }
    }

    private static String estimatorJSON () {
	    JSONObject obj = new JSONObject();
	    obj.put("ts", new Date().getTime());
	    obj.put("range", seconds);
	    obj.put("ec", lc_par);
	    String hexvalue =  byteArrayToHex(lc.getBytes());
	    obj.put("est", hexvalue);
            return obj.toJSONString();
    }

    public static void main(String[] args) throws Exception {
        boolean std_in = false;
        boolean std_out = false;
        KafkaStreams kafka_stream = null;
        BufferedReader br = null;

        if(args.length < 4) { 
            System.out.print("USAGE: bin/kafka-run-class.sh DataEstimator "); 
            System.out.println("in_topic|stdin out_topic|stdout bytes seconds"); 
            System.exit(1);
        }
        String src_stream = args[0];
        String dst_stream = args[1];
        bytes =  Integer.valueOf(args[2]).intValue();
        seconds = Integer.valueOf(args[3]).intValue();
        std_in = src_stream.equals("stdin");
        std_out = dst_stream.equals("stdout");
        json_key = "uid";
        hs_flag = false;
        hl_flag = false;
        lc_flag = true;

        if (!std_in) kafka_stream = initKafkaStream(src_stream, dst_stream);

        debug = true;
        if (std_in) {
            try {
                 initCounting();
                 br = new BufferedReader(new InputStreamReader(System.in));
                 String line = "";
                 while(true){
                     line = br.readLine();
                     if (line == null) break;
                     processCounting(line);
                 }
            } catch (Exception e) {
            }
        }
        else {
            kafka_stream.start();
        }

        if (std_in) {
            System.out.println(estimatorJSON());
        }

        Thread.sleep(2000000L);
        if (!std_in) kafka_stream.close();
    }
}
