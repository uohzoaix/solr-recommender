package finderbots.recommenders.hadoop;

/**
 * User: pat
 * Date: 4/5/13
 * Time: 9:33 AM
 */

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.mahout.math.VectorWritable;

import java.io.IOException;

class ToItemVectorsReducer extends Reducer<IntWritable, VectorWritable, IntWritable, VectorWritable> {

    @Override
    protected void reduce(IntWritable row, Iterable<VectorWritable> vectors, Context ctx)
        throws IOException, InterruptedException {
        VectorWritable vectorWritable = VectorWritable.merge(vectors.iterator());
        vectorWritable.setWritesLaxPrecision(true);
        ctx.write(row, vectorWritable);
    }
}
