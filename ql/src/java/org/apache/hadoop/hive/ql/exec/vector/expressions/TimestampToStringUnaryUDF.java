/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector.expressions;

import java.util.Arrays;

import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.TimestampColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorExpressionDescriptor;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;

/**
 * This is a superclass for unary long functions returning strings that operate directly on the
 * input and set the output.
 */
abstract public class TimestampToStringUnaryUDF extends VectorExpression {
  private static final long serialVersionUID = 1L;

  private final int inputColumn;

  public TimestampToStringUnaryUDF(int inputColumn, int outputColumnNum) {
    super(outputColumnNum);
    this.inputColumn = inputColumn;
  }

  public TimestampToStringUnaryUDF() {
    super();

    // Dummy final assignments.
    inputColumn = -1;
  }

  abstract protected void func(BytesColumnVector outputColVector, TimestampColumnVector inV, int i);

  @Override
  public void evaluate(VectorizedRowBatch batch) {

    if (childExpressions != null) {
      super.evaluateChildren(batch);
    }

    TimestampColumnVector inputColVector = (TimestampColumnVector) batch.cols[inputColumn];
    int[] sel = batch.selected;
    int n = batch.size;
    BytesColumnVector outputColVector = (BytesColumnVector) batch.cols[outputColumnNum];
    boolean[] inputIsNull = inputColVector.isNull;
    boolean[] outputIsNull = outputColVector.isNull;
    outputColVector.initBuffer();

    if (n == 0) {
      //Nothing to do
      return;
    }

    // We do not need to do a column reset since we are carefully changing the output.
    outputColVector.isRepeating = false;

    if (inputColVector.isRepeating) {
      if (inputColVector.noNulls || !inputIsNull[0]) {
        // Set isNull before call in case it changes it mind.
        outputIsNull[0] = false;
        func(outputColVector, inputColVector, 0);
      } else {
        outputIsNull[0] = true;
        outputColVector.noNulls = false;
      }
      outputColVector.isRepeating = true;
      return;
    }

    if (inputColVector.noNulls) {
      if (batch.selectedInUse) {

        // CONSIDER: For large n, fill n or all of isNull array and use the tighter ELSE loop.

        if (!outputColVector.noNulls) {
          for(int j = 0; j != n; j++) {
           final int i = sel[j];
           // Set isNull before call in case it changes it mind.
           outputIsNull[i] = false;
           func(outputColVector, inputColVector, i);
         }
        } else {
          for(int j = 0; j != n; j++) {
            final int i = sel[j];
            func(outputColVector, inputColVector, i);
          }
        }
      } else {
        if (!outputColVector.noNulls) {

          // Assume it is almost always a performance win to fill all of isNull so we can
          // safely reset noNulls.
          Arrays.fill(outputIsNull, false);
          outputColVector.noNulls = true;
        }
        for(int i = 0; i != n; i++) {
          func(outputColVector, inputColVector, i);
        }
      }
    } else /* there are nulls in the inputColVector */ {

      // Carefully handle NULLs...
      outputColVector.noNulls = false;

      if (batch.selectedInUse) {
        for(int j = 0; j != n; j++) {
          int i = sel[j];
          outputColVector.isNull[i] = inputColVector.isNull[i];
          if (!inputColVector.isNull[i]) {
            func(outputColVector, inputColVector, i);
          }
        }
      } else {
        System.arraycopy(inputColVector.isNull, 0, outputColVector.isNull, 0, n);
        for(int i = 0; i != n; i++) {
          if (!inputColVector.isNull[i]) {
            func(outputColVector, inputColVector, i);
          }
        }
      }
    }
  }

  public String vectorExpressionParameters() {
    return getColumnParamString(0, inputColumn);
  }

  @Override
  public VectorExpressionDescriptor.Descriptor getDescriptor() {
    VectorExpressionDescriptor.Builder b = new VectorExpressionDescriptor.Builder();
    b.setMode(VectorExpressionDescriptor.Mode.PROJECTION)
        .setNumArguments(1)
        .setArgumentTypes(
            VectorExpressionDescriptor.ArgumentType.TIMESTAMP)
        .setInputExpressionTypes(
            VectorExpressionDescriptor.InputExpressionType.COLUMN);
    return b.build();
  }
}