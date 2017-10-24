/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.cli.fs.command;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.client.ReadType;
import alluxio.client.file.FileInStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.URIStatus;
import alluxio.client.file.options.OpenFileOptions;
import alluxio.exception.AlluxioException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.status.InvalidArgumentException;

import com.google.common.io.Closer;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.io.IOException;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Loads a file or directory in Alluxio space, making it resident in Alluxio.
 */
@ThreadSafe
public final class LoadCommand extends WithWildCardPathCommand {
  private static final Option LOCAL_OPTION =
      Option.builder()
          .longOpt("local")
          .required(false)
          .hasArg(false)
          .desc("load the file to local worker.")
          .build();

  /**
   * Constructs a new instance to load a file or directory in Alluxio space.
   *
   * @param fs the filesystem of Alluxio
   */
  public LoadCommand(FileSystem fs) {
    super(fs);
  }

  @Override
  public String getCommandName() {
    return "load";
  }

  @Override
  public Options getOptions() {
    return new Options()
        .addOption(LOCAL_OPTION);
  }

  @Override
  protected void runCommand(AlluxioURI path, CommandLine cl) throws AlluxioException, IOException {
    load(path, cl.hasOption(LOCAL_OPTION.getOpt()));
  }

  /**
   * Loads a file or directory in Alluxio space, makes it resident in Alluxio.
   *
   * @param filePath The {@link AlluxioURI} path to load into Alluxio
   * @param local the local flag; If the file is already in Alluxio fully, enable the local flag,
   *              Alluxio will still load the file, otherwise Alluxio will do nothing.
   */
  private void load(AlluxioURI filePath, boolean local) throws AlluxioException, IOException {
    URIStatus status = mFileSystem.getStatus(filePath);
    if (status.isFolder()) {
      List<URIStatus> statuses = mFileSystem.listStatus(filePath);
      for (URIStatus uriStatus : statuses) {
        AlluxioURI newPath = new AlluxioURI(uriStatus.getPath());
        load(newPath, local);
      }
    } else {
      if (!local && status.getInAlluxioPercentage() == 100) {
        // The file has already been fully loaded into Alluxio.
        System.out.println(filePath + " already in Alluxio fully");
        return;
      }
      Closer closer = Closer.create();
      try {
        OpenFileOptions options = OpenFileOptions.defaults().setReadType(ReadType.CACHE_PROMOTE);
        FileInStream in = closer.register(mFileSystem.openFile(filePath, options));
        byte[] buf = new byte[8 * Constants.MB];
        while (in.read(buf) != -1) {
        }
      } catch (Exception e) {
        throw closer.rethrow(e);
      } finally {
        closer.close();
      }
    }
    System.out.println(filePath + " loaded");
  }

  @Override
  public String getUsage() {
    return "load [--local] <path>";
  }

  @Override
  public String getDescription() {
    return "Loads a file or directory in Alluxio space, makes it resident in Alluxio.";
  }

  @Override
  public void validateArgs(String... args) throws InvalidArgumentException {
    if (args.length < 1) {
      throw new InvalidArgumentException(ExceptionMessage.INVALID_ARGS_NUM_INSUFFICIENT
          .getMessage(getCommandName(), 1, args.length));
    }
  }
}
