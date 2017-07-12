/*
 * Copyright 2004 - 2012 Mirko Nasato and contributors
 *           2016 - 2017 Simon Braconnier and contributors
 *
 * This file is part of JODConverter - Java OpenDocument Converter.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jodconverter.task;

import static org.jodconverter.office.OfficeUtils.toUnoProperties;
import static org.jodconverter.office.OfficeUtils.toUrl;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.star.frame.XStorable;
import com.sun.star.io.IOException;
import com.sun.star.lang.IllegalArgumentException;
import com.sun.star.lang.XComponent;
import com.sun.star.task.ErrorCodeIOException;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.util.CloseVetoException;
import com.sun.star.util.XCloseable;

import org.jodconverter.filter.FilterChain;
import org.jodconverter.job.SourceDocumentSpecs;
import org.jodconverter.job.TargetDocumentSpecs;
import org.jodconverter.office.OfficeContext;
import org.jodconverter.office.OfficeException;
import org.jodconverter.office.OfficeTask;
import org.jodconverter.office.ValidateUtils;

/** Represents the default behavior for a conversion task. */
public class DefaultConversionTask implements OfficeTask {

  private static final String ERROR_MESSAGE_LOAD = "Could not open document: ";
  private static final String ERROR_MESSAGE_STORE = "Could not store document: ";

  private static final Logger logger = LoggerFactory.getLogger(DefaultConversionTask.class);

  private final SourceDocumentSpecs source;
  private final TargetDocumentSpecs target;
  private Map<String, Object> defaultLoadProperties;
  private FilterChain filterChain;

  /**
   * Creates a new conversion task from a specified source to a specified target.
   *
   * @param source The source specifications for the conversion.
   * @param target The target specifications for the conversion.
   * @param defaultLoadProperties the default properties to be applied when loading the document.
   *     These properties are added before the load properties of the document format specified in
   *     the {@code source} arguments.
   * @param filterChain filterChain to use with this task.
   */
  public DefaultConversionTask(
      final SourceDocumentSpecs source,
      final TargetDocumentSpecs target,
      final Map<String, Object> defaultLoadProperties,
      final FilterChain filterChain) {
    super();

    this.source = source;
    this.target = target;
    this.defaultLoadProperties = defaultLoadProperties;
    this.filterChain = filterChain;
  }

  @Override
  public void execute(final OfficeContext context) throws OfficeException {

    logger.info("Executing default conversion task...");

    XComponent document = null;
    try {
      document = loadDocument(context);
      modifyDocument(context, document);
      storeDocument(document);
    } catch (OfficeException officeEx) {
      throw officeEx;
    } catch (Exception ex) {
      throw new OfficeException("Conversion failed", ex);
    } finally {
      closeDocument(document);
    }
  }

  // Gets the office properties to apply when the input file will be loaded.
  private Map<String, Object> getLoadProperties() {

    final Map<String, Object> loadProperties = new HashMap<>();
    if (defaultLoadProperties != null) {
      loadProperties.putAll(defaultLoadProperties);
    }
    if (source.getFormat() != null && source.getFormat().getLoadProperties() != null) {
      loadProperties.putAll(source.getFormat().getLoadProperties());
    }
    return loadProperties;
  }

  // Gets the office properties to apply when the converted document will be saved as the output file.
  private Map<String, Object> getStoreProperties(final XComponent document) throws OfficeException {

    if (target.getFormat() != null) {
      return target.getFormat().getStoreProperties(OfficeTaskUtils.getDocumentFamily(document));
    }
    return null;
  }

  /**
   * Loads the document to convert.
   *
   * @param context the office context.
   * @return a XComponent that is the loaded document to convert.
   * @throws OfficeException if an error occurs.
   */
  protected XComponent loadDocument(final OfficeContext context) throws OfficeException {

    File sourceFile = source.getFile();

    // Check if the file exists
    ValidateUtils.fileExists(sourceFile, "Input document not found: %s");

    XComponent document = null;
    try {
      document =
          context
              .getComponentLoader()
              .loadComponentFromURL(
                  toUrl(sourceFile), "_blank", 0, toUnoProperties(getLoadProperties()));
    } catch (IllegalArgumentException illegalArgumentEx) {
      throw new OfficeException(ERROR_MESSAGE_LOAD + sourceFile.getName(), illegalArgumentEx);
    } catch (ErrorCodeIOException errorCodeIoEx) {
      throw new OfficeException(
          ERROR_MESSAGE_LOAD + sourceFile.getName() + "; errorCode: " + errorCodeIoEx.ErrCode,
          errorCodeIoEx);
    } catch (IOException ioEx) {
      throw new OfficeException(ERROR_MESSAGE_LOAD + sourceFile.getName(), ioEx);
    }

    // The document cannot be null
    ValidateUtils.notNull(document, ERROR_MESSAGE_LOAD + sourceFile.getName());
    return document;
  }

  /**
   * Override to modify the document after it has been loaded and before it gets saved in the new
   * format.
   *
   * @param context the office context.
   * @param document the office document.
   * @throws OfficeException if an error occurs.
   */
  protected void modifyDocument(final OfficeContext context, final XComponent document)
      throws OfficeException {

    if (filterChain != null) {
      filterChain.doFilter(context, document);
    }
  }

  /**
   * Stores the converted document as the output file.
   *
   * @param document the office document to store.
   * @throws OfficeException if an error occurs.
   */
  protected void storeDocument(final XComponent document) throws OfficeException {

    File targetFile = target.getFile();

    final Map<String, Object> storeProperties = getStoreProperties(document);

    // The properties cannot be null
    ValidateUtils.notNull(storeProperties, "Unsupported conversion");

    try {
      UnoRuntime.queryInterface(XStorable.class, document)
          .storeToURL(toUrl(targetFile), toUnoProperties(storeProperties));
    } catch (ErrorCodeIOException errorCodeIoEx) {
      throw new OfficeException(
          ERROR_MESSAGE_STORE + targetFile.getName() + "; errorCode: " + errorCodeIoEx.ErrCode,
          errorCodeIoEx);
    } catch (IOException ioEx) {
      throw new OfficeException(ERROR_MESSAGE_STORE + targetFile.getName(), ioEx);
    }
  }

  /**
   * Closes the converted document.
   *
   * @param document the office document to close.
   */
  protected void closeDocument(final XComponent document) {

    if (document != null) {

      // Closing the converted document. Use XCloseable.close if the
      // interface is supported, otherwise use XComponent.dispose
      final XCloseable closeable = UnoRuntime.queryInterface(XCloseable.class, document);
      if (closeable == null) {
        UnoRuntime.queryInterface(XComponent.class, document).dispose();
      } else {
        try {
          closeable.close(true);
        } catch (CloseVetoException closeVetoEx) { // NOSONAR
          // whoever raised the veto should close the document
        }
      }
    }
  }
}
