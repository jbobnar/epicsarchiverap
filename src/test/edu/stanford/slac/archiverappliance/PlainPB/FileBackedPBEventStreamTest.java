package edu.stanford.slac.archiverappliance.PlainPB;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.GregorianCalendar;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.epics.archiverappliance.Event;
import org.epics.archiverappliance.common.BasicContext;
import org.epics.archiverappliance.common.TimeUtils;
import org.epics.archiverappliance.config.ArchDBRTypes;
import org.epics.archiverappliance.config.ConfigServiceForTests;
import org.epics.archiverappliance.config.StoragePluginURLParser;
import org.epics.archiverappliance.data.ScalarValue;
import org.epics.archiverappliance.engine.membuf.ArrayListEventStream;
import org.epics.archiverappliance.retrieval.RemotableEventStreamDesc;
import org.epics.archiverappliance.utils.simulation.SimulationEvent;
import org.epics.archiverappliance.utils.simulation.SimulationEventStream;
import org.epics.archiverappliance.utils.simulation.SimulationEventStreamIterator;
import org.epics.archiverappliance.utils.simulation.SineGenerator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Some simple tests for the FileBackedPBEventStream
 * We generate a years worth of data and then create FileBackedPBEventStream's using various constructors and make sure we get the expected amount of data. 
 * @author mshankar
 *
 */
public class FileBackedPBEventStreamTest {
	private static Logger logger = Logger.getLogger(FileBackedPBEventStreamTest.class.getName());
	File testFolder = new File(ConfigServiceForTests.getDefaultPBTestFolder() + File.separator + "FileBackedPBEventStreamTest");
	String pvName = ConfigServiceForTests.ARCH_UNIT_TEST_PVNAME_PREFIX + ":FileBackedPBEventStreamTest";
	ArchDBRTypes dbrType = ArchDBRTypes.DBR_SCALAR_DOUBLE;
	PlainPBStoragePlugin storagePlugin;
	private boolean leapYear = new GregorianCalendar().isLeapYear(TimeUtils.getCurrentYear());
	private ConfigServiceForTests configService;


	@Before
	public void setUp() throws Exception {
		configService = new ConfigServiceForTests(new File("./bin"));
		storagePlugin = (PlainPBStoragePlugin) StoragePluginURLParser.parseStoragePlugin("pb://localhost?name=FileBackedPBEventStreamTest&rootFolder=" + testFolder.getAbsolutePath() + "&partitionGranularity=PARTITION_YEAR", configService);
		int phasediffindegrees = 10;
		SimulationEventStream simstream = new SimulationEventStream(dbrType, new SineGenerator(phasediffindegrees));
		try(BasicContext context = new BasicContext()) {
			storagePlugin.appendData(context, pvName, simstream);
		}
	}

	@After
	public void tearDown() throws Exception {
		FileUtils.deleteDirectory(testFolder);
	}
	
	@Test
	public void test() throws Exception {
		testLocationBasedEventBeforeTime();
		testCompleteStream();
		testLocationBasedIterator();
		testTimeBasedIterator();
		makeSureWeGetTheLastEventInTheFile();
		testHighRateEndLocation();
	}

	private void testCompleteStream() throws Exception {
		try(BasicContext context = new BasicContext()) {
			long startMs = System.currentTimeMillis();
			Path path = PlainPBPathNameUtility.getPathNameForTime(storagePlugin, pvName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
			assertTrue("Did we not write any data?", path != null);
			int eventCount = 0;
			try(FileBackedPBEventStream stream = new FileBackedPBEventStream(pvName, path, dbrType)) {
				for(Event e : stream) {
					e.getEventTimeStamp();
					eventCount++;
				}
			}
			int expectedSamples = new GregorianCalendar().isLeapYear(TimeUtils.getCurrentYear()) ? SimulationEventStreamIterator.LEAPYEAR_NUMBER_OF_SAMPLES : SimulationEventStreamIterator.DEFAULT_NUMBER_OF_SAMPLES;
			assertTrue("Expected " + expectedSamples + " got " + eventCount, eventCount == expectedSamples);
			long endMs = System.currentTimeMillis();
			logger.info("Time for " + eventCount + " samples = " + (endMs - startMs) + "(ms)");
		}
	}
	
	private void testLocationBasedIterator() throws Exception {
		try(BasicContext context = new BasicContext()) {
			Path path = PlainPBPathNameUtility.getPathNameForTime(storagePlugin, pvName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
			int eventCount = 0;
			try(FileBackedPBEventStream stream = new FileBackedPBEventStream(pvName, path, dbrType, 0, Files.size(path))) {
				for(@SuppressWarnings("unused") Event e : stream) {
					eventCount++;
				}
			}
			int expectedSamples = leapYear ? SimulationEventStreamIterator.LEAPYEAR_NUMBER_OF_SAMPLES : SimulationEventStreamIterator.DEFAULT_NUMBER_OF_SAMPLES;
			assertTrue("Expected " + expectedSamples + " got " + eventCount, eventCount == expectedSamples);
		}

		try(BasicContext context = new BasicContext()) {
			Path path = PlainPBPathNameUtility.getPathNameForTime(storagePlugin, pvName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
			int eventCount = 0;
			try(FileBackedPBEventStream stream = new FileBackedPBEventStream(pvName, path, dbrType, Files.size(path), Files.size(path)+1)) {
				for(@SuppressWarnings("unused") Event e : stream) {
					eventCount++;
				}
			}
			int expectedSamples = 0;
			assertTrue("Expected " + expectedSamples + " got " + eventCount, eventCount == expectedSamples);
		}
	}
	
	private void testTimeBasedIterator() throws Exception {
		for(int i = 0; i < 2; i++) {
			boolean skipSearch = (i==0);
			try(BasicContext context = new BasicContext()) {
				Path path = PlainPBPathNameUtility.getPathNameForTime(storagePlugin, pvName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
				int eventCount = 0;
				// Start 11 days into the year and get two days worth of data.
				long startEpochSeconds = TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*11;
				Timestamp start = TimeUtils.convertFromEpochSeconds(startEpochSeconds, 0);
				int secondsToExtract = 24*60*60*2;
				Timestamp end = TimeUtils.convertFromEpochSeconds(startEpochSeconds + secondsToExtract, 0);
				try(FileBackedPBEventStream stream = new FileBackedPBEventStream(pvName, path, dbrType, start, end, skipSearch)) {
					long eventEpochSeconds = 0;
					for(Event e : stream) {
						eventEpochSeconds = e.getEpochSeconds();
						if(eventCount < 2) {
							logger.info("Starting event timestamp " + TimeUtils.convertToISO8601String(eventEpochSeconds));
						} else if (eventCount > (secondsToExtract-10)) { 
							logger.info("Ending event timestamp " + TimeUtils.convertToISO8601String(eventEpochSeconds));
						}
						eventCount++;
					}
					logger.info("Final event timestamp " + TimeUtils.convertToHumanReadableString(eventEpochSeconds));
				}
				int expectedSamples = secondsToExtract + 1;
				assertTrue("Expected " + expectedSamples + " got " + eventCount + " with skipSearch " + skipSearch, eventCount == expectedSamples);
			}
			
			// Same as before expect the start time is before the year
			try(BasicContext context = new BasicContext()) {
				Path path = PlainPBPathNameUtility.getPathNameForTime(storagePlugin, pvName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
				int eventCount = 0;
				long startEpochSeconds = TimeUtils.getStartOfCurrentYearInSeconds() - 24*60*60;
				Timestamp start = TimeUtils.convertFromEpochSeconds(startEpochSeconds, 0);
				int secondsToExtract = 24*60*60*2;
				Timestamp end = TimeUtils.convertFromEpochSeconds(startEpochSeconds + secondsToExtract, 0);
				logger.debug("Looking for data between " + TimeUtils.convertToISO8601String(start) + " and " + TimeUtils.convertToISO8601String(end) + " with skipSearch " + skipSearch);
				try(FileBackedPBEventStream stream = new FileBackedPBEventStream(pvName, path, dbrType, start, end, skipSearch)) {
					long eventEpochSeconds = 0;
					for(Event e : stream) {
						eventEpochSeconds = e.getEpochSeconds();
						if(eventCount < 2) {
							logger.info("Starting event timestamp " + TimeUtils.convertToISO8601String(eventEpochSeconds));
						}
						eventCount++;
					}
					logger.info("Final event timestamp " + TimeUtils.convertToISO8601String(eventEpochSeconds));
				}
				// We should only get one days worth of data.
				int expectedSamples = 24*60*60;
				assertTrue("Expected " + expectedSamples + " got " + eventCount + " with skipSearch " + skipSearch, eventCount == expectedSamples);
			}

			// This time, change the end time
			try(BasicContext context = new BasicContext()) {
				Path path = PlainPBPathNameUtility.getPathNameForTime(storagePlugin, pvName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
				int eventCount = 0;
				long startEpochSeconds = TimeUtils.getStartOfCurrentYearInSeconds() + 360*24*60*60;
				Timestamp start = TimeUtils.convertFromEpochSeconds(startEpochSeconds, 0);
				int secondsToExtract = 24*60*60*10;
				Timestamp end = TimeUtils.convertFromEpochSeconds(startEpochSeconds + secondsToExtract, 0);
				long eventEpochSeconds = 0;
				try(FileBackedPBEventStream stream = new FileBackedPBEventStream(pvName, path, dbrType, start, end, skipSearch)) {
					for(Event e : stream) {
						eventEpochSeconds = e.getEpochSeconds();
						if(eventCount < 2) {
							logger.info("Starting event timestamp " + TimeUtils.convertToHumanReadableString(eventEpochSeconds));
						}
						eventCount++;
					}
				}
				logger.info("Final event timestamp " + TimeUtils.convertToHumanReadableString(eventEpochSeconds));
				// Based on whether this is a leap year, we should get 5-6 days worth of data
				int expectedSamples = leapYear ? 24*60*60*6 + 1  : 24*60*60*5 + 1;
				assertTrue("Expected " + expectedSamples + " got " + eventCount + " with skipSearch " + skipSearch, eventCount == expectedSamples);
			}
		}
	}
	
	private void testLocationBasedEventBeforeTime() throws IOException { 
		try(BasicContext context = new BasicContext()) {
			Path path = PlainPBPathNameUtility.getPathNameForTime(storagePlugin, pvName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
			// Start 11 days into the year and get two days worth of data.
			long epochSeconds = TimeUtils.getStartOfCurrentYearInSeconds() + 7*24*60*60;
			Timestamp time = TimeUtils.convertFromEpochSeconds(epochSeconds, 0);
			try(FileBackedPBEventStream stream = new FileBackedPBEventStream(pvName, path, dbrType, time, TimeUtils.getEndOfYear(TimeUtils.getCurrentYear()), false)) {
				boolean firstEvent = true;
				for(Event e : stream) {
					if(firstEvent) {
						assertTrue(
								"The first event should be before timestamp " 
										+ TimeUtils.convertToHumanReadableString(time) 
										+ " got " 
										+ TimeUtils.convertToHumanReadableString(e.getEventTimeStamp()), e.getEventTimeStamp().before(time));
						firstEvent = false;
					} else {
						// All other events should be after timestamp
						assertTrue(
								"All other events should be on or after timestamp " 
										+ TimeUtils.convertToHumanReadableString(time) 
										+ " got " 
										+ TimeUtils.convertToHumanReadableString(e.getEventTimeStamp()), e.getEventTimeStamp().after(time) || e.getEventTimeStamp().equals(time));
					}
				}
			}
		}
	}
	
	
	private void makeSureWeGetTheLastEventInTheFile() throws IOException { 
		try(BasicContext context = new BasicContext()) {
			Path path = PlainPBPathNameUtility.getPathNameForTime(storagePlugin, pvName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
			// Start near the end of the year
			long startEpochSeconds = TimeUtils.getStartOfCurrentYearInSeconds() + 360*24*60*60;
			Timestamp startTime = TimeUtils.convertFromEpochSeconds(startEpochSeconds, 0);
			Timestamp endTime = TimeUtils.convertFromEpochSeconds(startEpochSeconds + 20*24*60*60, 0);
			Event finalEvent = null;
			try(FileBackedPBEventStream stream = new FileBackedPBEventStream(pvName, path, dbrType, startTime, endTime, false)) {
				boolean firstEvent = true;
				for(Event e : stream) {
					if(firstEvent) {
						assertTrue(
								"The first event should be before timestamp " 
										+ TimeUtils.convertToHumanReadableString(startTime) 
										+ " got " 
										+ TimeUtils.convertToHumanReadableString(e.getEventTimeStamp()), e.getEventTimeStamp().before(startTime));
						firstEvent = false;
					} else {
						finalEvent = e.makeClone();
					}
				}
			}
			
			assertTrue("Final event is null", finalEvent != null);
			Timestamp finalSecondOfYear = TimeUtils.getEndOfYear(TimeUtils.getCurrentYear());
			finalSecondOfYear.setNanos(0);
			assertTrue("Final event should be the last event in the stream " 
					+ TimeUtils.convertToISO8601String(finalSecondOfYear) 
					+ " Instead it is " 
					+ TimeUtils.convertToISO8601String(finalEvent.getEventTimeStamp()), 
					finalEvent.getEventTimeStamp().equals(finalSecondOfYear));
			
		}
	}
	
	
	/**
	 * This is Jud Gauden'z use case. We have a high rate PV (more than one event per second). 
	 * We then ask for data from the same second (start time and end time is the same second).
	 * For this we generate data into a new PB file.
	 * @throws IOException
	 */
	private void testHighRateEndLocation() throws IOException { 
		PlainPBStoragePlugin highRatePlugin = (PlainPBStoragePlugin) StoragePluginURLParser.parseStoragePlugin("pb://localhost?name=FileBackedPBEventStreamTest&rootFolder=" + testFolder.getAbsolutePath() + "&partitionGranularity=PARTITION_YEAR", configService);
		String highRatePVName = ConfigServiceForTests.ARCH_UNIT_TEST_PVNAME_PREFIX + ":FileBackedPBEventStreamTestHighRate";
		int day = 60;
		int startofdayinseconds = day*24*60*60;
		try(BasicContext context = new BasicContext()) {
			short currentYear = TimeUtils.getCurrentYear();
			ArrayListEventStream testData = new ArrayListEventStream(24*60*60, new RemotableEventStreamDesc(ArchDBRTypes.DBR_SCALAR_DOUBLE, highRatePVName, currentYear));
			for(int secondintoday = 0; secondintoday < 24*60*60; secondintoday++) {
				// The value should be the secondsIntoYear integer divided by 600.
				// Add 10 events per second
				for(int i = 0; i < 10; i++) { 
					SimulationEvent sample = new SimulationEvent(startofdayinseconds + secondintoday, currentYear, ArchDBRTypes.DBR_SCALAR_DOUBLE, new ScalarValue<Double>((double) (((int)(startofdayinseconds + secondintoday)/600))));
					sample.setNanos(i*100);
					testData.add(sample);
				}
			}
			highRatePlugin.appendData(context, highRatePVName, testData);
		}
		
		long requestEpochSeconds = TimeUtils.getStartOfCurrentYearInSeconds() + startofdayinseconds + 12*60*60;
		// Yes; start and end time are the same epochseconds. We can vary the nanos.
		Timestamp startTime = TimeUtils.convertFromEpochSeconds(requestEpochSeconds, 0);
		Timestamp endTime   = TimeUtils.convertFromEpochSeconds(requestEpochSeconds, 999999999);

		try(BasicContext context = new BasicContext()) {
			Path path = PlainPBPathNameUtility.getPathNameForTime(highRatePlugin, highRatePVName, TimeUtils.getStartOfCurrentYearInSeconds() + 24*60*60*7, context.getPaths(), configService.getPVNameToKeyConverter());
			try(FileBackedPBEventStream stream = new FileBackedPBEventStream(highRatePVName, path, dbrType, startTime, endTime, false)) {
				boolean firstEvent = true;
				int eventCount = 0;
				int expectedEventCount = 11;
				for(Event e : stream) {
					eventCount++;
					if(firstEvent) {
						assertTrue(
								"The first event should be before timestamp " 
										+ TimeUtils.convertToHumanReadableString(startTime) 
										+ " got " 
										+ TimeUtils.convertToHumanReadableString(e.getEventTimeStamp()), e.getEventTimeStamp().before(startTime));
						firstEvent = false;
					}
				}
				assertTrue("We should have " + expectedEventCount + " events. Instead we got " + eventCount, eventCount == expectedEventCount);
			}
		}
	}

}
