package org.molgenis.r.service;

import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;

import com.google.common.base.Stopwatch;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.commons.io.IOUtils;
import org.molgenis.r.Formatter;
import org.molgenis.r.exceptions.RExecutionException;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.REXPMismatchException;
import org.rosuda.REngine.Rserve.RConnection;
import org.rosuda.REngine.Rserve.RFileInputStream;
import org.rosuda.REngine.Rserve.RFileOutputStream;
import org.rosuda.REngine.Rserve.RserveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class RExecutorServiceImpl implements RExecutorService {

  private static final Logger LOGGER = LoggerFactory.getLogger(RExecutorServiceImpl.class);
  public static final int RFILE_BUFFER_SIZE = 65536;

  @Override
  public REXP execute(String cmd, RConnection connection) {
    try {
      LOGGER.debug("Evaluate {}", cmd);
      REXP result = connection.eval(format("try({%s})", cmd));
      if (result == null) {
        throw new RExecutionException("Eval returned null");
      }
      if (result.inherits("try-error")) {
        throw new RExecutionException(
            stream(result.asStrings()).map(String::trim).collect(joining("; ")));
      }
      return result;
    } catch (RserveException | REXPMismatchException e) {
      throw new RExecutionException(e);
    }
  }

  @Override
  public void saveWorkspace(RConnection connection, Consumer<InputStream> inputStreamConsumer) {
    try {
      LOGGER.debug("Save workspace");
      String command = "base::save.image()";
      execute(command, connection);
      try (RFileInputStream is = connection.openFile(".RData")) {
        inputStreamConsumer.accept(is);
      }
    } catch (IOException e) {
      throw new RExecutionException(e);
    }
  }

  @Override
  public void loadWorkspace(RConnection connection, Resource resource, String environment) {
    LOGGER.debug("Load workspace into {}", environment);
    try {
      copyFile(resource, ".RData", connection);
      connection.eval(format("base::load(file='.RData', envir=%s)", environment));
      connection.eval("base::unlink('.RData')");
    } catch (IOException | RserveException e) {
      throw new RExecutionException(e);
    }
  }

  @Override
  public void loadTable(
      RConnection connection,
      Resource resource,
      String filename,
      String symbol,
      List<String> variables) {
    LOGGER.debug("Load table from file {} into {}", filename, symbol);
    String rFileName = filename.replace("/", "_");
    try {
      copyFile(resource, rFileName, connection);
      if (variables.isEmpty()) {
        execute(
            format(
                "is.null(base::assign('%s', value={arrow::read_parquet('%s')}))",
                symbol, rFileName),
            connection);
      } else {
        String colSelect =
            "tidyselect::any_of("
                + Formatter.stringVector(variables.toArray(new String[] {}))
                + ")";
        execute(
            format(
                "is.null(base::assign('%s', value={arrow::read_parquet('%s', col_select = %s)}))",
                symbol, rFileName, colSelect),
            connection);
      }
      execute(format("base::unlink('%s')", rFileName), connection);
    } catch (IOException e) {
      throw new RExecutionException(e);
    }
  }

  @Override
  public void loadResource(
      RConnection connection, Resource resource, String filename, String symbol) {
    LOGGER.debug("Load resource from file {} into {}", filename, symbol);
    String rFileName = filename.replace("/", "_");
    try {
      copyFile(resource, rFileName, connection);
      execute(
          format(
              "is.null(base::assign('%s', value={resourcer::newResourceClient(base::readRDS('%s'))}))",
              symbol, rFileName),
          connection);
      execute(format("base::unlink('%s')", rFileName), connection);
    } catch (IOException e) {
      throw new RExecutionException(e);
    }
  }

  void copyFile(Resource resource, String dataFileName, RConnection connection) throws IOException {
    LOGGER.info("Copying '{}' to R...", dataFileName);
    Stopwatch sw = Stopwatch.createStarted();
    try (InputStream is = resource.getInputStream();
        RFileOutputStream os = connection.createFile(dataFileName);
        BufferedOutputStream bos = new BufferedOutputStream(os, RFILE_BUFFER_SIZE)) {
      long size = IOUtils.copyLarge(is, bos);
      if (LOGGER.isDebugEnabled()) {
        var elapsed = sw.elapsed(TimeUnit.MICROSECONDS);
        LOGGER.debug(
            "Copied {} in {}ms [{} MB/s]",
            byteCountToDisplaySize(size),
            elapsed / 1000,
            format("%.03f", size * 1.0 / elapsed));
      }
    }
  }
}
