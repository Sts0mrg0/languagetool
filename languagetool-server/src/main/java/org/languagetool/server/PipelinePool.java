/*
 *  LanguageTool, a natural language style checker
 *  * Copyright (C) 2018 Fabian Richter
 *  *
 *  * This library is free software; you can redistribute it and/or
 *  * modify it under the terms of the GNU Lesser General Public
 *  * License as published by the Free Software Foundation; either
 *  * version 2.1 of the License, or (at your option) any later version.
 *  *
 *  * This library is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  * Lesser General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU Lesser General Public
 *  * License along with this library; if not, write to the Free Software
 *  * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 *  * USA
 *
 */

package org.languagetool.server;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.ResultCache;
import org.languagetool.UserConfig;
import org.languagetool.gui.Configuration;
import org.languagetool.tools.Tools;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Caches pre-configured JLanguageTool instances to avoid costly setup time of rules, etc.
 */
public class PipelinePool {

  public static class PipelineSettings {
    private Language lang;
    private Language motherTongue;
    private TextChecker.QueryParams query;
    private UserConfig user;

    PipelineSettings(Language lang, Language motherTongue, TextChecker.QueryParams params, UserConfig userConfig) {
      this.lang = lang;
      this.motherTongue = motherTongue;
      this.query = params;
      this.user = userConfig;
    }

    @Override
    public int hashCode() {
      return new HashCodeBuilder(17, 31)
        .append(lang)
        .append(motherTongue)
        .append(query)
        .append(user)
        .toHashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) return true;
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      PipelineSettings other = (PipelineSettings) obj;
      return new EqualsBuilder()
        .append(lang, other.lang)
        .append(motherTongue, other.motherTongue)
        .append(query, other.query)
        .append(user, other.user)
        .isEquals();
    }

    @Override
    public String toString() {
      return new ToStringBuilder(this)
        .append("lang", lang)
        .append("motherTongue", motherTongue)
        .append("query", query)
        .append("user", user)
        .build();
    }
  }

  private final HTTPServerConfig config;
  private final ResultCache cache;
  private final LoadingCache<PipelineSettings, ConcurrentLinkedQueue<Pipeline>> pool;
  private final boolean internalServer;
  // stats
  private long pipelinesUsed;
  private long requests;

  public PipelinePool(HTTPServerConfig config, ResultCache cache, boolean internalServer) {
    this.internalServer = internalServer;
    this.config = config;
    this.cache = cache;
    int maxPoolSize = config.getMaxPipelinePoolSize();
    int expireTime = config.getPipelineExpireTime();
    // TODO: figure out how to expire elements of the queue if many build up after a load spike
    // and then sit around unused later when there is steady, but low usage of the specific pipeline
    if (config.isPipelineCachingEnabled()) {
      this.pool = CacheBuilder.newBuilder()
        .maximumSize(maxPoolSize)
        .expireAfterAccess(expireTime, TimeUnit.SECONDS)
        .build(new CacheLoader<PipelineSettings, ConcurrentLinkedQueue<Pipeline>>() {
          @Override
          public ConcurrentLinkedQueue<Pipeline> load(PipelineSettings key) {
            return new ConcurrentLinkedQueue<>();
          }
        });
    } else {
      this.pool = null;
    }
  }

  public Pipeline getPipeline(PipelineSettings settings) throws Exception {
    if (pool != null) {
      requests++;
      ConcurrentLinkedQueue<Pipeline> pipelines = pool.get(settings);
      if (pipelines.size() > 10) {
        ServerTools.print(String.format("Pipeline buildup for %s: %d pipelines created.", settings, pipelines.size()));
      }
      if (requests % 1000 == 0) {
        ServerTools.print(String.format("Pipeline cache stats: %f hit rate", (double) pipelinesUsed / requests));
      }
      Pipeline pipeline = pipelines.poll();
      if (pipeline == null) {
        //ServerTools.print(String.format("No prepared pipeline found for %s; creating one.", settings));
        pipeline = createPipeline(settings.lang, settings.motherTongue, settings.query, settings.user);
      } else {
        pipelinesUsed++;
        //ServerTools.print(String.format("Prepared pipeline found for %s; using it.", settings));
      }
      return pipeline;
    } else {
      return createPipeline(settings.lang, settings.motherTongue, settings.query, settings.user);
    }
  }

  public void returnPipeline(PipelineSettings settings, Pipeline pipeline) throws ExecutionException {
    if (pool == null) return;
    ConcurrentLinkedQueue<Pipeline> pipelines = pool.get(settings);
    pipelines.add(pipeline);
  }

  /**
   * Create a JLanguageTool instance for a specific language, mother tongue, and rule configuration.
   * Uses Pipeline wrapper to safely share objects
   *
   * @param lang the language to be used
   * @param motherTongue the user's mother tongue or {@code null}
   */
  Pipeline createPipeline(Language lang, Language motherTongue, TextChecker.QueryParams params, UserConfig userConfig)
    throws Exception { // package-private for mocking
    Pipeline lt = new Pipeline(lang, params.altLanguages, motherTongue, cache, userConfig);
    lt.setMaxErrorsPerWordRate(config.getMaxErrorsPerWordRate());
    if (config.getLanguageModelDir() != null) {
      lt.activateLanguageModelRules(config.getLanguageModelDir());
    }
    if (config.getWord2VecModelDir () != null) {
      lt.activateWord2VecModelRules(config.getWord2VecModelDir());
    }
    if (config.getRulesConfigFile() != null) {
      configureFromRulesFile(lt, lang);
    } else {
      configureFromGUI(lt, lang);
    }
    if (params.useQuerySettings) {
      Tools.selectRules(lt, new HashSet<>(params.disabledCategories), new HashSet<>(params.enabledCategories),
        new HashSet<>(params.disabledRules), new HashSet<>(params.enabledRules), params.useEnabledOnly);
    }
    if (pool != null) {
      lt.setupFinished();
    }
    return lt;
  }

  private void configureFromRulesFile(JLanguageTool langTool, Language lang) throws IOException {
    ServerTools.print("Using options configured in " + config.getRulesConfigFile());
    // If we are explicitly configuring from rules, ignore the useGUIConfig flag
    if (config.getRulesConfigFile() != null) {
      org.languagetool.gui.Tools.configureFromRules(langTool, new Configuration(config.getRulesConfigFile()
        .getCanonicalFile().getParentFile(), config.getRulesConfigFile().getName(), lang));
    } else {
      throw new RuntimeException("config.getRulesConfigFile() is null");
    }
  }

  private void configureFromGUI(JLanguageTool langTool, Language lang) throws IOException {
    Configuration config = new Configuration(lang);
    if (internalServer && config.getUseGUIConfig()) {
      ServerTools.print("Using options configured in the GUI");
      org.languagetool.gui.Tools.configureFromRules(langTool, config);
    }
  }

}