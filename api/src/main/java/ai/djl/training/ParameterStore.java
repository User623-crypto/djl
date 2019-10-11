/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package ai.djl.training;

import ai.djl.Device;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.nn.Parameter;
import ai.djl.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ParameterStore {

    private NDManager manager;
    private Map<String, Pair<Boolean, List<NDArray>>> parameterMap;
    private Map<Device, Integer> deviceMap;
    private boolean copy;
    private ParameterServer parameterServer;

    public ParameterStore(NDManager manager, boolean copy) {
        this.manager = manager;
        this.copy = copy;
        parameterMap = new ConcurrentHashMap<>();
        deviceMap = new ConcurrentHashMap<>();
        deviceMap.put(manager.getDevice(), 0);
    }

    public void setParameterServer(ParameterServer parameterServer, Device[] devices) {
        this.parameterServer = parameterServer;
        deviceMap.clear();
        for (int i = 0; i < devices.length; ++i) {
            deviceMap.put(devices[i], i);
        }
    }

    public void updateAllParameters() {
        int priority = 0;
        for (Map.Entry<String, Pair<Boolean, List<NDArray>>> entry : parameterMap.entrySet()) {
            String parameterId = entry.getKey();
            Pair<Boolean, List<NDArray>> pair = entry.getValue();
            if (pair.getKey()) {
                NDArray[] grads =
                        pair.getValue().stream().map(NDArray::getGradient).toArray(NDArray[]::new);
                parameterServer.push(parameterId, grads, -priority);
                ++priority;
            }
        }

        priority = 0;
        for (Map.Entry<String, Pair<Boolean, List<NDArray>>> entry : parameterMap.entrySet()) {
            String parameterId = entry.getKey();
            Pair<Boolean, List<NDArray>> pair = entry.getValue();
            if (pair.getKey()) {
                NDArray[] values = pair.getValue().toArray(new NDArray[0]);
                parameterServer.pull(parameterId, values, -priority);
                ++priority;
            }
        }
    }

    public synchronized NDArray getValue(Parameter parameter, Device device) {
        String parameterId = parameter.getId();
        int index = deviceMap.get(device);
        Pair<Boolean, List<NDArray>> pair = parameterMap.get(parameterId);
        if (pair == null) {
            boolean requireGradient = parameter.requireGradient();
            pair = new Pair<>(requireGradient, new ArrayList<>());
            parameterMap.put(parameterId, pair);
        }

        List<NDArray> list = pair.getValue();
        if (list.isEmpty()) {
            NDArray array = parameter.getArray();
            if (parameterServer != null) {
                NDArray[] arrays = new NDArray[deviceMap.size()];
                for (Map.Entry<Device, Integer> entry : deviceMap.entrySet()) {
                    Device dev = entry.getKey();
                    int i = entry.getValue();
                    if (i == index) {
                        arrays[i] = array;
                    } else {
                        arrays[i] = array.asInDevice(dev, true);
                        arrays[i].attach(manager);
                    }
                    parameterServer.init(parameterId, arrays);
                    list.add(arrays[i]);
                }
            } else {
                if (copy || !array.getDevice().equals(device)) {
                    array = array.asInDevice(device, true);
                    array.attach(manager);
                }
                list.add(array);
            }
        }

        return list.get(index);
    }
}
