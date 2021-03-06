/*
 * Copyright 2018 Kantega AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kantega.reststop.statistics;

import org.kantega.reststop.api.*;
import org.kantega.reststop.servlet.api.FilterPhase;
import org.kantega.reststop.servlet.api.ServletBuilder;

import javax.servlet.Filter;

/**

 */
@Plugin
public class StatisticsPlugin {

    @Export
    private final Filter statsFilter;

    public StatisticsPlugin(ServletBuilder servletBuilder) {

        statsFilter = servletBuilder.filter(new StatisticsFilter(), FilterPhase.USER,"/*");
    }
}
