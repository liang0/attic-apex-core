package com.malhartech.stram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import junit.framework.Assert;

import org.junit.Test;

import com.malhartech.api.BaseOperator;
import com.malhartech.api.Context.OperatorContext;
import com.malhartech.api.DAG;
import com.malhartech.api.DefaultInputPort;
import com.malhartech.api.DefaultOutputPort;
import com.malhartech.api.InputOperator;
import com.malhartech.stram.PhysicalPlan.PTOperator;

public class PartitioningTest {

  public static class CollectorOperator extends BaseOperator
  {
    /*
     * Received tuples are stored in a map keyed with the system assigned operator id.
     */
    public static final Map<String, List<Object>> receivedTuples = new ConcurrentHashMap<String, List<Object>>();
    private transient String operatorId;

    @Override
    public void setup(OperatorContext context) {
      this.operatorId = context.getId();
    }

    public final transient DefaultInputPort<Object> input = new DefaultInputPort<Object>(this)
    {
      @Override
      public void process(Object tuple)
      {
        Assert.assertNotNull(CollectorOperator.this.operatorId);
        List<Object> l = receivedTuples.get(CollectorOperator.this.operatorId);
        if (l == null) {
          l = new ArrayList<Object>();
          receivedTuples.put(CollectorOperator.this.operatorId, l);
        }
        l.add(tuple);
      }
    };
  }

  public static class TestInputOperator<T> extends BaseOperator implements InputOperator
  {
    public final transient DefaultOutputPort<T> output = new DefaultOutputPort<T>(this);
    transient boolean first;
    transient long windowId;

    /**
     * Tuples to be emitted by the operator, with one entry per window.
     */
    public List<List<T>> testTuples;

    @Override
    public void emitTuples()
    {
      if (testTuples == null || testTuples.isEmpty()) {
        throw new RuntimeException(new InterruptedException("No more tuples to send!"));
      }

      if (first) {
        List<T> tuples = testTuples.remove(0);
        for (T t : tuples) {
          output.emit(t);
        }
        first = false;
      }
    }

    @Override
    public void beginWindow(long windowId)
    {
      this.windowId = windowId;
      first = true;
    }
  }

  @Test
  public void testDefaultPartitioning() throws Exception {
    DAG dag = new DAG();

    String[][] testData = {
        {"a", "b"}
    };

    TestInputOperator<String> input = dag.addOperator("input", new TestInputOperator<String>());
    input.testTuples = new ArrayList<List<String>>();
    for (String[] tuples : testData) {
      input.testTuples.add(new ArrayList<String>(Arrays.asList(tuples)));
    }
    CollectorOperator collector = dag.addOperator("collector", new CollectorOperator());
    dag.getOperatorWrapper(collector).getAttributes().attr(OperatorContext.INITIAL_PARTITION_COUNT).set(2);
    dag.addStream("fromInput", input.output, collector.input);

    StramLocalCluster lc = new StramLocalCluster(dag);
    lc.setHeartbeatMonitoringEnabled(false);
    lc.run();

    List<PTOperator> operators = lc.getPlanOperators(dag.getOperatorWrapper(collector));
    Assert.assertEquals("number operator instances " + operators, 2, operators.size());

    // one entry for each partition
    Assert.assertEquals("received tuples " + CollectorOperator.receivedTuples, 2, CollectorOperator.receivedTuples.size());
  }

}
