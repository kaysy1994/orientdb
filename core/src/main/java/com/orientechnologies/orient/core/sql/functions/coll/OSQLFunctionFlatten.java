/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.sql.functions.coll;

import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.sql.functions.OSQLFunctionAbstract;

/**
 * Flatten record : extract first element if object is a collection.
 * 
 */
public class OSQLFunctionFlatten extends OSQLFunctionAbstract {
  public static final String NAME = "flatten";

  public OSQLFunctionFlatten() {
    super(NAME, 1, 1);
  }

  @Override
  protected Object evaluateNow(OCommandContext context, Object candidate) {
    final Object value = children.get(0).evaluate(context, candidate);
    return value;
  }

  public String getSyntax() {
    return "Syntax error: flatten(<field>)";
  }

  @Override
  public OSQLFunctionFlatten copy() {
    final OSQLFunctionFlatten fct = new OSQLFunctionFlatten();
    fct.setAlias(getAlias());
    fct.getArguments().addAll(getArguments());
    return fct;
  }

}