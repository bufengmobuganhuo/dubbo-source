/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.metadata.definition.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 2015/1/27.
 */
public class ServiceDefinition implements Serializable {

    // 接口完全限定名
    private String canonicalName;
    // 服务接口所在的完整路径
    private String codeSource;
    // 接口中定义的全部方法的描述信息，如方法名称，参数类型，返回值类型
    private List<MethodDefinition> methods;
    // 接口定义中涉及的全部类型描述信息，包括方法的参数和字段，如果遇到复杂类型，TypeDefinition 会递归获取复杂类型内部的字段
    private List<TypeDefinition> types;

    public String getCanonicalName() {
        return canonicalName;
    }

    public String getCodeSource() {
        return codeSource;
    }

    public List<MethodDefinition> getMethods() {
        if (methods == null) {
            methods = new ArrayList<>();
        }
        return methods;
    }

    public List<TypeDefinition> getTypes() {
        if (types == null) {
            types = new ArrayList<>();
        }
        return types;
    }

    public String getUniqueId() {
        return canonicalName + "@" + codeSource;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public void setCodeSource(String codeSource) {
        this.codeSource = codeSource;
    }

    public void setMethods(List<MethodDefinition> methods) {
        this.methods = methods;
    }

    public void setTypes(List<TypeDefinition> types) {
        this.types = types;
    }

    @Override
    public String toString() {
        return "ServiceDefinition [canonicalName=" + canonicalName + ", codeSource=" + codeSource + ", methods="
                + methods + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServiceDefinition)) {
            return false;
        }
        ServiceDefinition that = (ServiceDefinition) o;
        return Objects.equals(getCanonicalName(), that.getCanonicalName()) &&
                Objects.equals(getCodeSource(), that.getCodeSource()) &&
                Objects.equals(getMethods(), that.getMethods()) &&
                Objects.equals(getTypes(), that.getTypes());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getCanonicalName(), getCodeSource(), getMethods(), getTypes());
    }
}
