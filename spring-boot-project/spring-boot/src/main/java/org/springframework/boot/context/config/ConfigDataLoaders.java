/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;

import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.util.Instantiator;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.log.LogMessage;
import org.springframework.util.Assert;

/**
 * A collection of {@link ConfigDataLoader} instances loaded via {@code spring.factories}.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class ConfigDataLoaders {

	private final Log logger;

	private final boolean allLocationsOptional;

	private final List<ConfigDataLoader<?>> loaders;

	private final List<Class<?>> locationTypes;

	/**
	 * Create a new {@link ConfigDataLoaders} instance.
	 * @param allLocationsOptional if all locations are considered optional
	 * @param logFactory the deferred log factory
	 */
	ConfigDataLoaders(DeferredLogFactory logFactory, boolean allLocationsOptional) {
		this(logFactory, allLocationsOptional, SpringFactoriesLoader.loadFactoryNames(ConfigDataLoader.class, null));
	}

	/**
	 * Create a new {@link ConfigDataLoaders} instance.
	 * @param logFactory the deferred log factory
	 * @param allLocationsOptional if all locations are considered optional
	 * @param names the {@link ConfigDataLoader} class names instantiate
	 */
	ConfigDataLoaders(DeferredLogFactory logFactory, boolean allLocationsOptional, List<String> names) {
		this.logger = logFactory.getLog(getClass());
		this.allLocationsOptional = allLocationsOptional;
		Instantiator<ConfigDataLoader<?>> instantiator = new Instantiator<>(ConfigDataLoader.class,
				(availableParameters) -> availableParameters.add(Log.class, logFactory::getLog));
		this.loaders = instantiator.instantiate(names);
		this.locationTypes = getLocationTypes(this.loaders);
	}

	private List<Class<?>> getLocationTypes(List<ConfigDataLoader<?>> loaders) {
		List<Class<?>> locationTypes = new ArrayList<>(loaders.size());
		for (ConfigDataLoader<?> loader : loaders) {
			locationTypes.add(getLocationType(loader));
		}
		return Collections.unmodifiableList(locationTypes);
	}

	private Class<?> getLocationType(ConfigDataLoader<?> loader) {
		return ResolvableType.forClass(loader.getClass()).as(ConfigDataLoader.class).resolveGeneric();
	}

	/**
	 * Load {@link ConfigData} using the first appropriate {@link ConfigDataLoader}.
	 * @param <L> the config data location type
	 * @param context the loader context
	 * @param location the location to load
	 * @return the loaded {@link ConfigData}
	 * @throws IOException on IO error
	 */
	<L extends ConfigDataLocation> ConfigData load(ConfigDataLoaderContext context, L location) throws IOException {
		boolean optional = location instanceof OptionalConfigDataLocation;
		location = (!optional) ? location : OptionalConfigDataLocation.unwrap(location);
		return load(context, optional, location);
	}

	private <L extends ConfigDataLocation> ConfigData load(ConfigDataLoaderContext context, boolean optional,
			L location) throws IOException {
		ConfigDataLoader<L> loader = getLoader(context, location);
		this.logger.trace(LogMessage.of(() -> "Loading " + location + " using loader " + loader.getClass().getName()));
		try {
			return loader.load(context, location);
		}
		catch (ConfigDataLocationNotFoundException ex) {
			if (this.allLocationsOptional || optional) {
				this.logger.trace(LogMessage.format("Skipping missing resource from optional location %s", location));
				return null;
			}
			throw ex;
		}
	}

	@SuppressWarnings("unchecked")
	private <L extends ConfigDataLocation> ConfigDataLoader<L> getLoader(ConfigDataLoaderContext context, L location) {
		ConfigDataLoader<L> result = null;
		for (int i = 0; i < this.loaders.size(); i++) {
			ConfigDataLoader<?> candidate = this.loaders.get(i);
			if (this.locationTypes.get(i).isInstance(location)) {
				ConfigDataLoader<L> loader = (ConfigDataLoader<L>) candidate;
				if (loader.isLoadable(context, location)) {
					if (result != null) {
						throw new IllegalStateException("Multiple loaders found for location " + location + " ["
								+ candidate.getClass().getName() + "," + result.getClass().getName() + "]");
					}
					result = loader;
				}
			}
		}
		Assert.state(result != null, () -> "No loader found for location '" + location + "'");
		return result;
	}

}
