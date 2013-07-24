package finderbots.recommenders.hadoop;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;
import org.apache.mahout.common.AbstractJob;
import org.apache.mahout.common.HadoopUtil;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * User: pat
 * Date: 4/9/13
 * Time: 8:05 AM
 * Takes a directory of files named 'ext-id.txt' and splits them by action so they can
 * be fed to the recommender training jobs. This is done with HDFS tsv files as input and creates
 * the same as output.
 *
 * Todo: create cascading jobs to mapreduce this operation. The trick for a simple job is to
 *    put the indexes in memory and make them available to the m/r jobs so they can be added to
 *    then write them out as the last phase of the job.
 * Todo: turn this into a job of its own so it needn't be done for each run of the recommender
 *    since it takes a bit of time to split HDFS files in this single threaded manner.
 */

public class CombinedRetailActionFileSplitterJob extends AbstractJob {
    //public static final String ACTION_1 = "PURCHASE";
    //public static final String ACTION_2 = "ADD_TO_CART";
    //public static final String ACTION_3 = "VIEW_DETAIL";
    private static Logger LOGGER = Logger.getRootLogger();

    public static enum Action { PURCHASE, ADD_TO_CART, VIEW_DETAIL }

    public static final String ACTION_1_DIR = "purchase";
    public static final String ACTION_2_DIR = "view";
    public static final String ACTION_3_DIR = "add-to-cart";
    public static final String ACTION_OTHER_DIR = "other";
    public static final String ACTION_EVAL_DIR = "eval";
    public static final String ACTION_1_FILE = "purchases.csv";
    public static final String ACTION_2_FILE = "views.csv";
    public static final String ACTION_3_FILE = "add-to-carts.csv";
    public static final String ACTION_OTHER_FILE = "other.csv";
    public static final String NUM_USERS_FILE = "num-users.bin";
    public static final String NUM_ITEMS_FILE = "num-items.bin";
    public static final String ACTION_1_EXTERNAL_ID_FILE = "purchase-ext-id.tsv";
    public static final String ACTION_2_EXTERNAL_ID_FILE = "view-ext-id.tsv";
    public static final int ACTION_COLUMN = 2;
    public static final int TIMESTAMP_COLUMN = 0;
    public static final int ITEM_ID_COLUMN = 1;
    public static final int USER_ID_COLUMN = 4;
    public static final String INPUT_DELIMETER = "\t";
    public static final String OUTPUT_DELIMETER = ",";
    public static final String USER_INDEX_PATH = "user-index";
    public static final String ITEM_INDEX_PATH = "item-index";

    private BiMap<String, String> userIndex;
    private BiMap<String, String> itemIndex;
    private CookedFileSplitterJobOptions options;


    public void split(Path baseInputDir, Path baseOutputDir) throws IOException {
        FileSystem fs = baseInputDir.getFileSystem(getConf());
        Path action1DirPath = new Path(baseOutputDir, ACTION_1_DIR);
        Path action2DirPath = new Path(baseOutputDir, ACTION_2_DIR);
        Path action3DirPath = new Path(baseOutputDir, ACTION_3_DIR);
        Path actionOtherDirPath = new Path(baseOutputDir, ACTION_OTHER_DIR);
        Path evalDirPath = new Path(baseOutputDir, ACTION_EVAL_DIR);
        Path action1FilePath = new Path(action1DirPath, ACTION_1_FILE);
        Path action2FilePath = new Path(action2DirPath, ACTION_2_FILE);
        Path action3FilePath = new Path(action3DirPath, ACTION_3_FILE);
        Path actionOtherFilePath = new Path(actionOtherDirPath, ACTION_OTHER_FILE);
        Path evalAction1FilePath = new Path(evalDirPath, ACTION_1_EXTERNAL_ID_FILE);
        Path evalAction2FilePath = new Path(evalDirPath, ACTION_2_EXTERNAL_ID_FILE);
        FSDataOutputStream action1File;
        FSDataOutputStream action2File;
        FSDataOutputStream action3File;
        FSDataOutputStream actionOtherFile;

        //Eval output in case we use them for MAP
        //1357152044224	00090772-d326-4ae0-a8d9-219d3b938a0c	ShowItemDetail	782430
        FSDataOutputStream evalAction1File;//purchases with external ids, used for eval
        FSDataOutputStream evalAction2File;//purchases with external ids, used for queries in eval
        if (!fs.exists(baseOutputDir)) {
            LOGGER.info("Preference output dir:"+baseOutputDir.toString()+" does not exist. creating it.");
            fs.mkdirs(baseOutputDir);
        }

        if(fs.exists(action1DirPath)) fs.delete(action1DirPath, true);
        if(fs.exists(action2DirPath)) fs.delete(action2DirPath, true);
        if(fs.exists(action3DirPath)) fs.delete(action3DirPath, true);
        if(fs.exists(actionOtherDirPath)) fs.delete(actionOtherDirPath, true);
        if(fs.exists(evalDirPath)) fs.delete(evalDirPath, true);

        // cleaned out prefs if the existed, now create a place to put the new ones
        fs.mkdirs(action1DirPath);
        fs.mkdirs(action2DirPath);
        fs.mkdirs(action3DirPath);
        fs.mkdirs(actionOtherDirPath);
        fs.mkdirs(evalDirPath);
        action1File = fs.create(action1FilePath);
        action2File = fs.create(action2FilePath);
        action3File = fs.create(action3FilePath);
        actionOtherFile = fs.create(actionOtherFilePath);
        evalAction1File = fs.create(evalAction1FilePath);
        evalAction2File = fs.create(evalAction2FilePath);

        List<FSDataInputStream> cookedFiles = getCookedFiles(baseInputDir);

        Integer uniqueUserIDCounter = 0;
        Integer uniqueItemIDCounter = 0;
        for (FSDataInputStream stream : cookedFiles) {
            BufferedReader bin = new BufferedReader(new InputStreamReader(stream));
            String actionLogLine;
            while ((actionLogLine = bin.readLine()) != null) {//get user to make a rec for
                String[] columns = actionLogLine.split(INPUT_DELIMETER);
                String timestamp = columns[TIMESTAMP_COLUMN].trim();
                String externalUserIDString = columns[USER_ID_COLUMN].trim();
                String externalItemIDString = columns[ITEM_ID_COLUMN].trim();
                String actionString = columns[ACTION_COLUMN].trim();

                // create a bi-directional index of enternal->internal ids
                String internalUserID;
                String internalItemID;
                if (this.userIndex.containsKey(externalUserIDString)) {// already in the index
                    internalUserID = this.userIndex.get(externalUserIDString);
                } else {
                    internalUserID = uniqueUserIDCounter.toString();
                    this.userIndex.forcePut(externalUserIDString, internalUserID);
                    uniqueUserIDCounter += 1;
                    if(uniqueUserIDCounter % 10000 == 0) LOGGER.info("Splitter processed: "+Integer.toString(uniqueUserIDCounter)+" unique users.");
                }
                if (this.itemIndex.containsKey(externalItemIDString)) {// already in the index
                    internalItemID = this.itemIndex.get(externalItemIDString);
                } else {
                    internalItemID = uniqueItemIDCounter.toString();
                    this.itemIndex.forcePut(externalItemIDString, internalItemID);
                    uniqueItemIDCounter += 1;
                }
                switch (Action.valueOf(actionString)) {
                    case PURCHASE:
                        action1File.writeBytes(internalUserID + OUTPUT_DELIMETER + internalItemID + OUTPUT_DELIMETER + "1.0\n");
                        evalAction1File.writeBytes(timestamp + INPUT_DELIMETER + externalUserIDString + INPUT_DELIMETER + actionString + INPUT_DELIMETER + externalItemIDString + "\n");
                        break;
                    case VIEW_DETAIL:
                        action2File.writeBytes(internalUserID + OUTPUT_DELIMETER + internalItemID + OUTPUT_DELIMETER + "1.0\n");
                        evalAction2File.writeBytes(timestamp + INPUT_DELIMETER + externalUserIDString + INPUT_DELIMETER + actionString + INPUT_DELIMETER + externalItemIDString + "\n");

                        break;
                    case ADD_TO_CART:
                        if(options.getKeepPurchaseAndAddToCartTogether()){//merge purchase and atc
                            action1File.writeBytes(internalUserID + OUTPUT_DELIMETER + internalItemID + OUTPUT_DELIMETER + "1.0\n");
                        } else { //keep the actions separate
                            action3File.writeBytes(internalUserID + OUTPUT_DELIMETER + internalItemID + OUTPUT_DELIMETER + "1.0\n");
                        }
                        break;
                    default:
                        actionOtherFile.writeBytes(actionLogLine);//write what's not recognized
                        break;
                }
            }
        }
        action1File.close();
        action2File.close();
        action3File.close();
        actionOtherFile.close();
        evalAction1File.close();
        evalAction2File.close();
        int i = 0;
    }

    public void saveIndexes(Path where) throws IOException {
        Path userIndexPath = new Path(where, USER_INDEX_PATH);
        Path itemIndexPath = new Path(where, ITEM_INDEX_PATH);
        FileSystem fs = where.getFileSystem(new JobConf());
        if (fs.getFileStatus(where).isDir()) {
            FSDataOutputStream userIndexFile = fs.create(userIndexPath);
            this.saveIndex(userIndex, userIndexFile);
            FSDataOutputStream itemIndexFile = fs.create(itemIndexPath);
            this.saveIndex(itemIndex, itemIndexFile);
        } else {
            throw new IOException("Bad locaton for ID Indeces: " + where.toString());
        }
    }

    public void saveIndex(BiMap<String, String> map, FSDataOutputStream file) throws IOException {

        for (Map.Entry e : map.entrySet()) {
            file.writeBytes(e.getKey().toString() + OUTPUT_DELIMETER + e.getValue().toString() + "\n");
        }
        file.close();
    }

    public int getNumberOfUsers() {
        return this.userIndex.size();
    }

    public int getNumberOfItems() {
        return this.itemIndex.size();
    }

    public List<FSDataInputStream> getCookedFiles(Path baseInputDir) throws IOException {
        List<FSDataInputStream> files = new ArrayList<FSDataInputStream>();
        FileSystem fs = baseInputDir.getFileSystem(getConf());
        if (fs.getFileStatus(baseInputDir).isDir()) {
            FileStatus[] stats = fs.listStatus(baseInputDir);
            for (FileStatus fstat : stats) {
                if (fstat.getPath().getName().contains("cooked.txt") && !fstat.isDir()) {
                    files.add(fs.open(fstat.getPath()));
                } else if (fstat.isDir()) {
                    files.addAll(getCookedFiles(fstat.getPath()));
                }
            }
        }
        return files;
    }

    @Override
    public int run(String[] args) throws Exception {
        options = new CookedFileSplitterJobOptions();
        CmdLineParser parser = new CmdLineParser(options);
        String s = options.toString();

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            return -1;
        }

        this.userIndex = HashBiMap.create();
        this.itemIndex = HashBiMap.create();

        // split into actions and store in subdirs
        // create and index for users and another for items
        Path inputPath = new Path(options.getInputDirPath());
        FileSystem fs = inputPath.getFileSystem(new JobConf());
        Path outputPath = new Path(options.getOutputDirPath());
        // todo: can put this into m/r if it helps speed up
        split(inputPath, outputPath);// split into actions and store in subdirs

        Path indexesPath = new Path(options.getIndexDirPath());
        Path userIndexPath = new Path(options.getIndexDirPath(), USER_INDEX_PATH );
        Path itemIndexPath = new Path(options.getIndexDirPath(), ITEM_INDEX_PATH );
        if (fs.exists(userIndexPath)) fs.delete(userIndexPath, false);//delete file only!
        if (fs.exists(itemIndexPath)) fs.delete(itemIndexPath, false);//delete file only!
        // get the size of the matrixes and put them where the calling job
        // can find them
        HadoopUtil.writeInt(getNumberOfUsers(), new Path(indexesPath, NUM_USERS_FILE), getConf());
        HadoopUtil.writeInt(getNumberOfItems(), new Path(indexesPath, NUM_ITEMS_FILE), getConf());
        //write the indexes to tsv files
        saveIndexes(indexesPath);
        return 0;
    }

    public static void main(String[] args) throws Exception {
        ToolRunner.run(new Configuration(), new CombinedRetailActionFileSplitterJob(), args);
    }

    // Command line options for this job. Execute the main method above with no parameters
    // to get a help listing.
    //

    public class CookedFileSplitterJobOptions {

        //private String tempPath;
        private String inputDirPath;
        private String outputDirPath;
        private String userIndexDirPath;
        private String indexDirPath;
        private String itemIndexDirPath;
        private Boolean keepPurchaseAndAddToCartTogether;

        private static final String DEFAULT_TEMP_PATH = "tmp";

        CookedFileSplitterJobOptions() {
            //this.tempPath = DEFAULT_TEMP_PATH;
            this.keepPurchaseAndAddToCartTogether = false;
        }

        public Boolean getKeepPurchaseAndAddToCartTogether() {
            return keepPurchaseAndAddToCartTogether;
        }

        @Option(name = "--keepPurchaseAndAddToCartTogether", usage = "Merge the purchase actions and add-to-cart actions into the primary preferences file.", required = false)
        public void setKeepPurchaseAndAddToCartTogether(Boolean keepPurchaseAndAddToCartTogether) {
            this.keepPurchaseAndAddToCartTogether = keepPurchaseAndAddToCartTogether;
        }

        public String getIndexDirPath() {
            return indexDirPath;
        }

        @Option(name = "--indexesDir", usage = "Place for external to internal item and user ID indexes. This directory will be deleted before the indexes are written", required = true)
        public void setIndexDirPath(String indexDirPath) {
            this.indexDirPath = indexDirPath;
        }

        public String getUserIndexDirPath() {
            return userIndexDirPath;
        }

        public void setUserIndexDirPath(String userIndexDirPath) {
            this.userIndexDirPath = userIndexDirPath;
        }

        public String getItemIndexDirPath() {
            return itemIndexDirPath;
        }

        public void setItemIndexDirPath(String itemIndexDirPath) {
            this.itemIndexDirPath = itemIndexDirPath;
        }
/*
        @Option(name = "--tempDir", usage = "Place for intermediate data. Left after job but erased before starting (optional). Default: 'tmp'", required = false)
        public void setTempPath(String tempPath) {
            this.tempPath = tempPath;
        }

        public String getTempPath() {
            return this.tempPath;
        }
*/
        @Option(name = "--inputDir", usage = "Dir that will be searched recursively for files that have mixed actions. These will be split into the output dir.", required = true)
        public void setInputDirPath(String primaryInputDirPath) {
            this.inputDirPath = primaryInputDirPath;
        }

        public String getInputDirPath() {
            return this.inputDirPath;
        }

        @Option(name = "--outputDir", usage = "Output directory for recs.", required = true)
        public void setOutputDirPath(String outputDirPath) {
            this.outputDirPath = outputDirPath;
        }

        public String getOutputDirPath() {
            return this.outputDirPath;
        }

        @Override
        public String toString() {
            String options = ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE);
            options = options.replaceAll("\n", "\n#");
            Date date = new Date();
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy h:mm:ss a");
            String formattedDate = sdf.format(date);
            options = options + "\n# Timestamp for data creation = " + formattedDate;
            return options = new StringBuffer(options).insert(0, "#").toString();
        }
    }
}
