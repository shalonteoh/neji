/*
 * Copyright (c) 2016 BMD Software and University of Aveiro.
 *
 * Neji is a flexible and powerful platform for biomedical information extraction from text.
 *
 * This project is licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported License.
 * To view a copy of this license, visit http://creativecommons.org/licenses/by-nc-sa/3.0/.
 *
 * This project is a free software, you are free to copy, distribute, change and transmit it.
 * However, you may not use it for commercial purposes.
 *
 * It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package pt.ua.tm.neji.web.batch;

import org.apache.commons.lang.time.StopWatch;
import org.apache.tools.ant.filters.StringInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.ua.tm.neji.context.Context;
import pt.ua.tm.neji.context.OutputFormat;
import pt.ua.tm.neji.core.batch.BatchExecutor;
import pt.ua.tm.neji.core.corpus.Corpus;
import pt.ua.tm.neji.core.pipeline.Pipeline;
import pt.ua.tm.neji.core.processor.Processor;
import pt.ua.tm.neji.exception.NejiException;
import pt.ua.tm.neji.pipeline.DefaultPipeline;
import pt.ua.tm.neji.web.services.Service;

import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.io.output.ByteArrayOutputStream;
import pt.ua.tm.neji.context.ContextConfiguration;
import pt.ua.tm.neji.context.InputFormat;

/**
 * Implementation of an executor of document batches running on a deployed server.
 *
 * TODO: Text is processed and written in all available OutputFormats,
 * but only one format is returned (the one specified in the constructor)!
 * Must implement some cache system to save all outputs and return them if the 
 * user requires them!
 *
 * @author Eduardo Duarte (<a href="mailto:emod@ua.pt">emod@ua.pt</a>)
 * @author André Santos (<a href="mailto:andre.jeronimo@ua.pt">andre.jeronimo@ua.pt</a>))
 * @version 2.0
 */
public class ServerBatchExecutor extends BatchExecutor {

    private static Logger logger = LoggerFactory.getLogger(ServerBatchExecutor.class);

    private final Service service;
    private final ExecutorService executor;
    private final StringInputStream inputStream;
    private String annotatedText;
    private Corpus corpus;
    private Map<String, Boolean> groups;
    private boolean filterGroups;
    private OutputFormat format;
    private InputFormat inputFormat;
    private Map<String, String> extraParameters;


    public ServerBatchExecutor(final Service service, final ExecutorService executor, 
            final String text, final Map<String, Boolean> groups, 
            final InputFormat inputFormat, final OutputFormat outputFormat,
            Map<String, String> extraParameters) {

        // Verify if there are groups selected
        if ((groups == null) || groups.isEmpty()) {
            this.filterGroups = false;
        } else {
            this.filterGroups = true;
        }

        this.corpus = new Corpus();
        this.service = service;
        this.executor = executor;
        this.inputStream = new StringInputStream(text, "UTF-8");
        this.groups = groups;
        this.inputFormat = inputFormat;
        this.format = outputFormat;
        this.extraParameters = extraParameters;
    }

    @Override
    public void run(Class<? extends Processor> processorCls, Context context, Object... args) throws NejiException {

        // Add false positives
        if (service.getFalsePositives() != null) {
            byte[] fpByte = service.getFalsePositives().getBytes();
            context.getConfiguration().setFalsePositives(fpByte);
        } else {
            context.getConfiguration().setFalsePositives(null);
        }
        
        // Add semantic groups normalization (just when exporting, if format 
        // equals to null, then is an annotate)        
        if ((format != null) && !service.getGroupsNormalization().isEmpty()) {
            byte[] gnByte = service.getGroupsNormalizationByteArray();
            context.getConfiguration().setSemanticGroupsNormalization(gnByte);
        } else {
            context.getConfiguration().setSemanticGroupsNormalization(null);
        }
        
        // Add abreviations and disambiguation
        context.getConfiguration().setAbbreviations(service.getAbbreviations());
        context.getConfiguration().setDisambiguation(service.getDisambiguation());
        
        // Update configuration with the input format
        ContextConfiguration config = context.getConfiguration();
        if (!config.getInputFormat().equals(inputFormat)) {
            ContextConfiguration newConfig = new ContextConfiguration.Builder()
                    .withInputFormat(inputFormat)
                    .withOutputFormats(config.getOutputFormats())
                    .withParserTool(config.getParserTool())
                    .withParserLanguage(config.getParserLanguage())
                    .withParserLevel(config.getParserLevel())
                    .build();
            
            context.setConfiguration(newConfig);
        }
        
        // Extra parameters
        String[] xmltags = null;
        if (extraParameters != null && !extraParameters.isEmpty()) {
                        
            if (extraParameters.containsKey("xmltags")) {
                xmltags = extraParameters.get("xmltags").trim().split(",");
            }
        }
        
        // Distribution of output streams to the pipeline
        Map<OutputFormat, OutputStream> formatToStreamMap = new HashMap<>();
        List<OutputStream> outputStreams = new ArrayList<>();

        for (OutputFormat f : context.getConfiguration().getOutputFormats()) {
            OutputStream o = new ByteArrayOutputStream();
            formatToStreamMap.put(f, o);
            outputStreams.add(o);
        }

        Processor processor;
        Pipeline p = new DefaultPipeline(corpus);
        try {
            if (xmltags != null) {
                processor = newProcessor(processorCls, context, inputStream, outputStreams, service, 
                        p, groups, filterGroups, xmltags);
            } else {
                processor = newProcessor(processorCls, context, inputStream, outputStreams, service, 
                        p, groups, filterGroups);
            }
        } catch (NejiException ex) {
            String m = "There was a problem creating the server processor";
            logger.error(m, ex);
            throw new RuntimeException(m, ex);
        }

        logger.info("");
        logger.info("Started processing a new document...");
        StopWatch timer = new StopWatch();
        timer.start();

        executor.execute(processor);

        try {
            synchronized (processor) {
                processor.wait();
            }
        } catch (InterruptedException ex) {
            throw new RuntimeException("There was a problem running the annotation service.", ex);
        }

        timer.stop();
        logger.info("Processed document in {}", timer.toString());

        if (format != null) {
            OutputStream output = formatToStreamMap.get(format);
            annotatedText = output.toString();
        }
    }

    public String getAnnotatedText() {
        return annotatedText;
    }

    @Override
    public List<Corpus> getProcessedCorpora() {
        return Arrays.asList(corpus);
    }
}
