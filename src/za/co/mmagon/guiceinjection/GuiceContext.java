/*
 * Copyright (C) 2017 Marc Magon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package za.co.mmagon.guiceinjection;

import com.google.inject.*;
import com.google.inject.servlet.GuiceServletContextListener;
import com.oracle.jaxb21.Persistence;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import za.co.mmagon.guiceinjection.abstractions.GuiceSiteInjectorModule;
import za.co.mmagon.guiceinjection.annotations.GuicePostStartup;
import za.co.mmagon.guiceinjection.annotations.GuicePreStartup;
import za.co.mmagon.guiceinjection.annotations.JaxbContext;
import za.co.mmagon.guiceinjection.enumerations.FastAccessFileTypes;

import javax.servlet.ServletContextEvent;
import javax.validation.constraints.NotNull;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.File;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Provides an interface for reflection and injection in one.
 * <p>
 * Use reflect() to access the class library, or inject() to get the injector for any instance
 *
 * @author GedMarc
 * @version 1.0
 * @since Nov 14, 2016
 */
public class GuiceContext extends GuiceServletContextListener
{
	private static final Logger log = Logger.getLogger("GuiceContext");
	/**
	 * This particular instance of the class
	 */
	private static GuiceContext instance;
	public static final Key<JAXBContext> PERSISTENCE_CONTEXT_KEY = Key.get(JAXBContext.class, JaxbContext.class);

	/**
	 * A list of all the specifically excluded jar files (to skip unzip)
	 */
	private static volatile Map<FastAccessFileTypes, Map<String, byte[]>> fastAccessFiles;
	private static ExecutorService asynchronousFileLoader = Executors.newWorkStealingPool();
	private static ExecutorService asynchronousPersistenceFileLoader = Executors.newWorkStealingPool();

	/**
	 * The building injector
	 */
	private static boolean buildingInjector = false;
	/**
	 * If the references are built or not
	 */
	private static boolean built = false;

	static
	{
		for (Handler handler : LogManager.getLogManager().getLogger("").getHandlers())
		{
			handler.setFormatter(new LogSingleLineFormatter());
		}
	}

	private static JAXBContext persistenceContext;

	/**
	 * The physical injector for the JVM container
	 */
	private Injector injector;

	/**
	 * The actual scanner
	 */
	private static FastClasspathScanner scanner;
	/**
	 * The scan result built from everything - the core scanner.
	 */
	private static ScanResult scanResult;

	/**
	 * Facade layer for backwards compatibility
	 */
	private Reflections reflections;

	/**
	 * A list of jars to exclude from the scan file for the application
	 */
	private static List<String> excludeJarsFromScan;

	/**
	 * Creates a new Guice context. Not necessary
	 */
	private GuiceContext()
	{
		if (instance == null)
		{
			instance = this;
			log.info("Starting up JAXB Persistence");
			Runnable loadAsync = new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						persistenceContext = JAXBContext.newInstance(Persistence.class);
					}
					catch (JAXBException e)
					{
						persistenceContext = null;
					}
				}
			};
			asynchronousPersistenceFileLoader.submit(loadAsync);
		}
	}

	/**
	 * Reconstructs the class scanner
	 */
	public static void buildScanner()
	{
		setScanner(new FastClasspathScanner());
		buildScanner();
	}

	/**
	 * Reference the Injector Directly
	 *
	 * @return
	 */
	@NotNull
	@SuppressWarnings("unchecked")
	public static synchronized Injector inject()
	{
		if (!built && !buildingInjector && context().injector == null)
		{
			buildingInjector = true;
			log.info("Starting up Injections");
			log.config("Startup Executions....");
			Set<Class<? extends GuicePreStartup>> pres = reflect().getSubTypesOf(GuicePreStartup.class);
			List<GuicePreStartup> startups = new ArrayList<>();
			for (Class<? extends GuicePreStartup> pre : pres)
			{
				GuicePreStartup pr = GuiceContext.getInstance(pre);
				startups.add(pr);
			}
			startups.sort(Comparator.comparing(GuicePreStartup::sortOrder));
			log.log(Level.FINE, "Total of [{0}] startup modules.", startups.size());
			startups.forEach(GuicePreStartup::onStartup);
			log.info("Finished Startup Execution");

			log.info("Loading All Default Binders (that extend GuiceDefaultBinder)");

			za.co.mmagon.guiceinjection.abstractions.GuiceInjectorModule defaultInjection;
			defaultInjection = new za.co.mmagon.guiceinjection.abstractions.GuiceInjectorModule();
			log.info("Loading All Site Binders (that extend GuiceSiteBinder)");

			GuiceSiteInjectorModule siteInjection;
			siteInjection = new GuiceSiteInjectorModule();

			int customModuleSize = reflect().getTypesAnnotatedWith(za.co.mmagon.guiceinjection.annotations.GuiceInjectorModule.class).size();
			log.log(Level.CONFIG, "Loading [{0}] Custom Modules", customModuleSize);
			ArrayList<Module> customModules = new ArrayList<>();
			Module[] cModules;

			for (Class<?> aClass : reflect().getTypesAnnotatedWith(za.co.mmagon.guiceinjection.annotations.GuiceInjectorModule.class))
			{
				try
				{
					Class<? extends AbstractModule> next = (Class<? extends AbstractModule>) aClass;
					log.log(Level.CONFIG, "Adding Module [{0}]", next.getCanonicalName());
					Module moduleInstance = next.newInstance();
					customModules.add(moduleInstance);
				}
				catch (InstantiationException | IllegalAccessException ex)
				{
					Logger.getLogger(GuiceContext.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
			customModules.add(0, siteInjection);
			customModules.add(0, defaultInjection);

			cModules = new Module[customModules.size()];
			cModules = customModules.toArray(cModules);

			context().injector = Guice.createInjector(cModules);
			buildingInjector = false;
			log.info("Post Startup Executions....");
			Set<Class<? extends GuicePostStartup>> closingPres = reflect().getSubTypesOf(GuicePostStartup.class);
			List<GuicePostStartup> postStartups = new ArrayList<>();
			closingPres.forEach(closingPre -> postStartups.add(GuiceContext.getInstance(closingPre)));
			postStartups.sort(Comparator.comparing(GuicePostStartup::sortOrder));
			log.log(Level.CONFIG, "Total of [{0}] startup modules.", postStartups.size());
			postStartups.forEach(GuicePostStartup::postLoad);
			log.info("Finished Post Startup Execution");
			log.info("Finished with Guice");
			built = true;
		}
		else
		{
			log.fine("Premature call to GuiceContext.inject. Injector is still currently building, are you calling guice context from a constructor? consider using init() or preconfigure()");
		}

		buildingInjector = false;
		return context().injector;
	}

	/**
	 * Execute on Destroy
	 */
	public static void destroy()
	{
		context().reflections = null;
		context().scanResult = null;
		context().scanner = null;
		context().injector = null;
		GuiceContext.instance = null;
	}

	/**
	 * If the context is built
	 *
	 * @return
	 */
	public static boolean isBuilt()
	{
		return built;
	}

	/**
	 * Gets a new injected instance of a class
	 *
	 * @param <T>
	 * @param type
	 *
	 * @return
	 */
	@NotNull
	public static <T> T getInstance(Class<T> type)
	{
		return inject().getInstance(type);
	}

	/**
	 * Gets a new specified instance from a give key
	 *
	 * @param <T>
	 * @param type
	 *
	 * @return
	 */
	@NotNull
	public static <T> T getInstance(Key<T> type)
	{
		return inject().getInstance(type);
	}

	/**
	 * If context can be used
	 *
	 * @return
	 */
	public static boolean isReady()
	{
		return isBuilt() && isBuildingInjector();
	}

	/**
	 * If the context is built
	 *
	 * @param built
	 */
	public static void setBuilt(boolean built)
	{
		GuiceContext.built = built;
	}

	/**
	 * If the context is currently still building the injector
	 *
	 * @return
	 */
	public static boolean isBuildingInjector()
	{
		return buildingInjector;
	}

	/**
	 * Returns an asychronous file load Execution Service (work steal)
	 *
	 * @return
	 */
	public static ExecutorService getAsynchronousFileLoader()
	{
		return asynchronousFileLoader;
	}

	/**
	 * Returns a threaded loader specifically for persistence units Execution Service (work steal)
	 * Allows you to load models asynchronously to your application
	 *
	 * @return
	 */
	public static ExecutorService getAsynchronousPersistenceFileLoader()
	{
		return asynchronousPersistenceFileLoader;
	}

	/**
	 * Returns the current scan result
	 *
	 * @return
	 */
	public ScanResult getScanResult()
	{
		if (scanResult == null)
		{
			GuiceStartup();
		}
		return scanResult;
	}

	/**
	 * Sets the current scan result
	 *
	 * @param scanResult
	 */
	public void setScanResult(ScanResult scanResult)
	{
		GuiceContext.context().scanResult = scanResult;
	}

	/**
	 * Returns the current classpath scanner
	 *
	 * @return
	 */
	public FastClasspathScanner getScanner()
	{
		return scanner;
	}

	/**
	 * Sets the classpath scanner
	 *
	 * @param scanner
	 */
	public static void setScanner(FastClasspathScanner scanner)
	{
		context().scanner = scanner;
	}

	/**
	 * Returns the actual context instance, provides access to methods existing a bit deeper
	 *
	 * @return
	 */
	public static GuiceContext context()
	{
		if (instance == null)
		{
			instance = new GuiceContext();
		}
		return instance;
	}

	/**
	 * Initializes Guice Context post Startup Beans
	 *
	 * @param servletContextEvent
	 */
	@Override
	public void contextInitialized(ServletContextEvent servletContextEvent)
	{
		inject();
	}

	/**
	 * Maps the injector class to the injector
	 *
	 * @return
	 */
	@Override
	public Injector getInjector()
	{
		return inject();
	}

	/**
	 * Returns the fully populated reflections object
	 *
	 * @return
	 */
	public Reflections getReflections()
	{
		return reflect();
	}

	/**
	 * Builds a reflection object if one does not exist
	 *
	 * @return
	 */
	public static Reflections reflect()
	{
		if (context().reflections == null)
		{
			context().reflections = new Reflections();
		}
		return context().reflections;
	}

	/**
	 * Returns the instance of the JAXB Context
	 *
	 * @return
	 */
	public static JAXBContext getPersistenceContext()
	{
		return persistenceContext;
	}

	private static void GuiceStartup()
	{
		log.info("Starting up classpath scanner");
		LocalDateTime start = LocalDateTime.now();
		if (excludeJarsFromScan == null || excludeJarsFromScan.isEmpty())
		{
			excludeJarsFromScan = new ArrayList<>();

			excludeJarsFromScan.add("-com.fasterxml.jackson");
			excludeJarsFromScan.add("-com.google.common");
			excludeJarsFromScan.add("-com.google.inject");
			excludeJarsFromScan.add("-com.microsoft.sqlserver");
			excludeJarsFromScan.add("-com.sun.enterprise.glassfish");
			excludeJarsFromScan.add("-com.sun.enterprise.module");
			excludeJarsFromScan.add("-com.sun.jdi");
			excludeJarsFromScan.add("-edu.umd.cs.findbugs");
			excludeJarsFromScan.add("-io.github.lukehutch.fastclasspathscanner");
			excludeJarsFromScan.add("-javassist");
			excludeJarsFromScan.add("-net.sf.qualitycheck");
			excludeJarsFromScan.add("-net.sf.uadetector");
			excludeJarsFromScan.add("-org.aopalliance");
			excludeJarsFromScan.add("-org.apache.catalina");
			excludeJarsFromScan.add("-org.apache.commons");
			excludeJarsFromScan.add("-org.apache.derby");
			excludeJarsFromScan.add("-org.glassfish");

			excludeJarsFromScan.add("-org.atmosphere");
			excludeJarsFromScan.add("-com.google.j2objc");
			excludeJarsFromScan.add("-com.sun.grizzly");


			excludeJarsFromScan.add("-com.google.thirdparty");
			excludeJarsFromScan.add("-com.intellij");
			excludeJarsFromScan.add("-com.jcabi");
			excludeJarsFromScan.add("-junit");
			excludeJarsFromScan.add("-org.apache.log4j");
			excludeJarsFromScan.add("-org.apache.tools");
			excludeJarsFromScan.add("-org.apiguardian");
			excludeJarsFromScan.add("-org.aspectj");
			excludeJarsFromScan.add("-org.assertj");
			excludeJarsFromScan.add("-org.hamcrest");
			excludeJarsFromScan.add("-org.junit");
			excludeJarsFromScan.add("-org.mockito");
			excludeJarsFromScan.add("-org.objenesis");
			excludeJarsFromScan.add("-org.opentest4j");
			excludeJarsFromScan.add("-FormPreviewFrame");
			excludeJarsFromScan.add("-FormPreviewFrame$");
			excludeJarsFromScan.add("-FormPreviewFrame$MyExitAction");
			excludeJarsFromScan.add("-FormPreviewFrame$MyPackAction");
			excludeJarsFromScan.add("-FormPreviewFrame$MySetLafAction");
			excludeJarsFromScan.add("-org.jacoco");
			excludeJarsFromScan.add("-com.vladium.emma");

			//glassfish jar defaultsglassfish.jar
			excludeJarsFromScan.add("-org.ietf");
			excludeJarsFromScan.add("-org.jboss");
			excludeJarsFromScan.add("-org.jvnet");
			excludeJarsFromScan.add("-org.slf4j");
			excludeJarsFromScan.add("-org.w3c");
			excludeJarsFromScan.add("-org.xml.sax");
			excludeJarsFromScan.add("-com.fasterxml.jackson");
			excludeJarsFromScan.add("-com.google.common");
			excludeJarsFromScan.add("-com.google.inject");
			excludeJarsFromScan.add("-com.microsoft.sqlserver");
			excludeJarsFromScan.add("-com.sun.enterprise.glassfish");
			excludeJarsFromScan.add("-com.sun.enterprise.module");
			excludeJarsFromScan.add("-com.sun.jdi");
			excludeJarsFromScan.add("-edu.umd.cs.findbugs");
			excludeJarsFromScan.add("-io.github.lukehutch.fastclasspathscanner");
			excludeJarsFromScan.add("-javassist");
			excludeJarsFromScan.add("-net.sf.qualitycheck");
			excludeJarsFromScan.add("-net.sf.uadetector");
			excludeJarsFromScan.add("-org.aopalliance");
			excludeJarsFromScan.add("-org.apache.catalina");
			excludeJarsFromScan.add("-org.apache.commons");
			excludeJarsFromScan.add("-org.apache.derby");
			excludeJarsFromScan.add("-org.glassfish");

			//glassfish jar defaultsglassfish.jar
			excludeJarsFromScan.add("-org.ietf");
			excludeJarsFromScan.add("-org.jboss");
			excludeJarsFromScan.add("-org.jvnet");
			excludeJarsFromScan.add("-org.slf4j");
			excludeJarsFromScan.add("-org.w3c");
			excludeJarsFromScan.add("-org.xml.sax");

			//JBoss Stuffs
			excludeJarsFromScan.add("-com.sun");
			excludeJarsFromScan.add("-junit.framework");
			excludeJarsFromScan.add("-junit.runner");
			excludeJarsFromScan.add("-junit.textui");
			excludeJarsFromScan.add("-org.apache");
			excludeJarsFromScan.add("-org.hamcrest");
			excludeJarsFromScan.add("-org.junit");
			excludeJarsFromScan.add("-junit.extensions");
			excludeJarsFromScan.add("-com.google.thirdparty");
			excludeJarsFromScan.add("-asposewobfuscated");
			excludeJarsFromScan.add("-com.hazelcast");
			excludeJarsFromScan.add("-org.dom4j");
			excludeJarsFromScan.add("-net.sf.jasperreports");
			excludeJarsFromScan.add("-org.mozilla.javascript");
			excludeJarsFromScan.add("-org.openxmlformats");
			excludeJarsFromScan.add("-com.aspose");
			excludeJarsFromScan.add("-com.lowagie");
			excludeJarsFromScan.add("-antlr");
			excludeJarsFromScan.add("-com.concerto");
			excludeJarsFromScan.add("-com.itextpdf");
			excludeJarsFromScan.add("-org.eclipse");
			excludeJarsFromScan.add("-org.exolab");
			excludeJarsFromScan.add("-org.hibernate");
			excludeJarsFromScan.add("-org.jfree");
			excludeJarsFromScan.add("-org.tartarus");
			excludeJarsFromScan.add("-org.olap4j");
			excludeJarsFromScan.add("-org.jfree");
			excludeJarsFromScan.add("-org.joda.time");
			excludeJarsFromScan.add("-org.xmlpull");
			excludeJarsFromScan.add("-schemasMicrosoftComOfficeExcel");
			excludeJarsFromScan.add("-schemasMicrosoftComVml");
			excludeJarsFromScan.add("-za.co.xds.web");
			excludeJarsFromScan.add("-com.thoughtworks.xstream");
			excludeJarsFromScan.add("-za.co.xds.schema");

			excludeJarsFromScan.add("-bitronix");
			excludeJarsFromScan.add("-com.fasterxml");
			excludeJarsFromScan.add("-com.google.gson");
			excludeJarsFromScan.add("-com.google.j2objc");
			excludeJarsFromScan.add("-com.google.javascript");
			excludeJarsFromScan.add("-com.google.protobuf");
			excludeJarsFromScan.add("-com.ibm.as400");
			excludeJarsFromScan.add("-com.ibm");
			excludeJarsFromScan.add("-com.jcraft");
			excludeJarsFromScan.add("-com.mchange");
			excludeJarsFromScan.add("-com.typesafe");
			excludeJarsFromScan.add("-com.unboundid");
			excludeJarsFromScan.add("-mediautil");
			excludeJarsFromScan.add("-microsoft.exchange");
			excludeJarsFromScan.add("-org.antlr");
			excludeJarsFromScan.add("-org.atmosphere");
			excludeJarsFromScan.add("-org.bouncycastle");
			excludeJarsFromScan.add("-org.datacontract");
			excludeJarsFromScan.add("-org.drools");
			excludeJarsFromScan.add("-org.hornetq");
			excludeJarsFromScan.add("-org.jasypt");
			excludeJarsFromScan.add("-org.jaxen");
			excludeJarsFromScan.add("-org.jbpm");
			excludeJarsFromScan.add("-org.jdom");
			excludeJarsFromScan.add("-org.jsoup");
			excludeJarsFromScan.add("-org.mortbay");
			excludeJarsFromScan.add("-org.mozilla");
			excludeJarsFromScan.add("-org.mvel2");
			excludeJarsFromScan.add("-org.omnifaces");
			excludeJarsFromScan.add("-org.primefaces");
			excludeJarsFromScan.add("-org.snmp4j");
			excludeJarsFromScan.add("-org.supercsv");
			excludeJarsFromScan.add("-repackage");
			excludeJarsFromScan.add("-schemaorg_apache_xmlbeans");
			excludeJarsFromScan.add("-schemasMicrosoftComOfficeOffice");
			excludeJarsFromScan.add("-utilities");
			excludeJarsFromScan.add("-weblogic");
			excludeJarsFromScan.add("-yodlee");
			excludeJarsFromScan.add("-za.co.xds.schema");

			excludeJarsFromScan.add("-org.mockito");
			excludeJarsFromScan.add("-org.jsr107");
			excludeJarsFromScan.add("-org.h2");
			excludeJarsFromScan.add("-org.codehaus");
			excludeJarsFromScan.add("-org.assertj");
			excludeJarsFromScan.add("-lombok");

			excludeJarsFromScan.add("-com.intellij");
			excludeJarsFromScan.add("-org.intellij");
			excludeJarsFromScan.add("-org.jetbrains");
			excludeJarsFromScan.add("-com.microsoft");
			excludeJarsFromScan.add("-com.nimbusds");
			excludeJarsFromScan.add("-groovy");
			excludeJarsFromScan.add("-groovyjarjarantlr");
			excludeJarsFromScan.add("-groovyjarjarasm");
			excludeJarsFromScan.add("-org.objenesis");
			excludeJarsFromScan.add("-net.minidev");
			excludeJarsFromScan.add("-microsoft.sql");
			excludeJarsFromScan.add("-org.opentest4j");
			excludeJarsFromScan.add("-com.oracle.jaxb.jaxb");
			excludeJarsFromScan.add("-eft");
			excludeJarsFromScan.add("-Driver");
			excludeJarsFromScan.add("-FormPreviewFrame");

			excludeJarsFromScan.add("-com.beust.jcommander");
			excludeJarsFromScan.add("-__redirected");
			excludeJarsFromScan.add("-com.github.jaiimageio");
			excludeJarsFromScan.add("-com.google.zxing");
			excludeJarsFromScan.add("-net.sf.ehcache");
			excludeJarsFromScan.add("-generated");

			excludeJarsFromScan.add("-*.jpg");
			excludeJarsFromScan.add("-*.jpeg");
			excludeJarsFromScan.add("-*.gif");
			excludeJarsFromScan.add("-*.png");
			excludeJarsFromScan.add("-*.xhtml");
			excludeJarsFromScan.add("-*.jsf");
			excludeJarsFromScan.add("-*.jsp");
			excludeJarsFromScan.add("-*.svg");
			excludeJarsFromScan.add("-*.txt");
			excludeJarsFromScan.add("-*.js");
			excludeJarsFromScan.add("-*.css");
			excludeJarsFromScan.add("-*.scss");
			excludeJarsFromScan.add("-*.pdf");
			excludeJarsFromScan.add("-*.xsd");

			excludeJarsFromScan.add("-*pom.xml");
			excludeJarsFromScan.add("-*pom.properties");
			excludeJarsFromScan.add("-*jboss.xml");
			excludeJarsFromScan.add("-*jboss-app.xml");
		}

		String[] exclusions = new String[excludeJarsFromScan.size()];
		exclusions = excludeJarsFromScan.toArray(exclusions);

		scanner = new FastClasspathScanner(exclusions);
		scanner.enableFieldInfo();
		scanner.enableFieldAnnotationIndexing();
		scanner.enableFieldTypeIndexing();
		scanner.enableMethodAnnotationIndexing();
		scanner.enableMethodInfo();
		scanner.ignoreFieldVisibility();
		scanner.ignoreMethodVisibility();
		registerScanQuickFiles(scanner);
		scanResult = scanner.scan();
		LocalDateTime finish = LocalDateTime.now();
		scanResult.getNamesOfAllStandardClasses().forEach(log::finest);

		log.info("Classpath Scanner Completed. Took [" + (finish.getLong(ChronoField.MILLI_OF_SECOND) - start.getLong(ChronoField.MILLI_OF_SECOND)) + "] millis.");
	}

	/**
	 * Registers the quick scan files
	 *
	 * @param scanner
	 */
	private static void registerScanQuickFiles(FastClasspathScanner scanner)
	{
		for (FastAccessFileTypes type : FastAccessFileTypes.values())
		{
			if (getFastAccessFiles().get(type) == null)
			{
				getFastAccessFiles().put(type, new LinkedHashMap<>());
			}
			for (String typeExtension : type.getEndsWith().split(";"))
			{
				scanner.matchFilenamePathLeaf(typeExtension, (File classpathElt, String relativePath, byte[] fileContents) ->
				{
					String idName = relativePath.substring(0, relativePath.lastIndexOf('.'));
					Map<String, byte[]> fastFiles = getFastAccessFiles().get(FastAccessFileTypes.Sql);
					fastFiles.put(idName, fileContents);
					log.warning(type.name() + " File Loaded : " + idName);
				});
			}
		}
	}

	/**
	 * Sets the given injector to this context
	 *
	 * @param injector
	 */
	public void setInjector(Injector injector)
	{
		this.injector = injector;
	}

	/**
	 * Returns the volatile list of fast access files (all the fast access file types)
	 *
	 * @return
	 */
	public static Map<FastAccessFileTypes, Map<String, byte[]>> getFastAccessFiles()
	{
		if (fastAccessFiles == null)
		{
			fastAccessFiles = new EnumMap<>(FastAccessFileTypes.class);
		}
		return fastAccessFiles;
	}

	/**
	 * Sets the volatile list of fast access files (all the fast access file types)
	 *
	 * @param fastAccessFiles
	 */
	public static void setFastAccessFiles(Map<FastAccessFileTypes, Map<String, byte[]>> fastAccessFiles)
	{
		GuiceContext.fastAccessFiles = fastAccessFiles;
	}
}
