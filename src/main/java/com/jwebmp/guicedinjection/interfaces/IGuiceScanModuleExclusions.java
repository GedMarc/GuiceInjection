package com.jwebmp.guicedinjection.interfaces;

import javax.validation.constraints.NotNull;
import java.util.Set;

/**
 * Marks JAR files referenced from libraries to be excluded from all scans
 */
@FunctionalInterface
public interface IGuiceScanModuleExclusions
		extends IDefaultService<IGuiceScanModuleExclusions>
{
	/**
	 * Excludes the given jars for scanning
	 *
	 * @return A set
	 */
	@NotNull Set<String> excludeModules();
}