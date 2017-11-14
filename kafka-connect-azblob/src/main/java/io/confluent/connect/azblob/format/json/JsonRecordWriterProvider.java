/*
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.connect.azblob.format.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.connect.azblob.storage.AzBlobOutputStream;
import io.confluent.connect.azblob.storage.AzBlobStorage;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import io.confluent.connect.azblob.AzBlobSinkConnectorConfig;
import io.confluent.connect.storage.format.RecordWriter;
import io.confluent.connect.storage.format.RecordWriterProvider;

public class JsonRecordWriterProvider implements RecordWriterProvider<AzBlobSinkConnectorConfig> {

  private static final Logger log = LoggerFactory.getLogger(JsonRecordWriterProvider.class);
  private static final String EXTENSION = ".json";
  private static final String LINE_SEPARATOR = System.lineSeparator();
  private static final byte[] LINE_SEPARATOR_BYTES = LINE_SEPARATOR.getBytes();
  private final AzBlobStorage storage;
  private final ObjectMapper mapper;
  private final JsonConverter converter;

  JsonRecordWriterProvider(AzBlobStorage storage, JsonConverter converter) {
    this.storage = storage;
    this.mapper = new ObjectMapper();
    this.converter = converter;
  }

  @Override
  public String getExtension() {
    return EXTENSION;
  }

  @Override
  public RecordWriter getRecordWriter(final AzBlobSinkConnectorConfig conf, final String filename) {
    try {
      return new RecordWriter() {
        final AzBlobOutputStream s3out = storage.create(filename, true);
        final JsonGenerator writer = mapper.getFactory()
                                         .createGenerator(s3out)
                                         .setRootValueSeparator(null);

        @Override
        public void write(SinkRecord record) {
          log.trace("Sink record: {}", record);
          try {
            Object value = record.value();
            if (value instanceof Struct) {
              byte[] rawJson = converter.fromConnectData(record.topic(), record.valueSchema(), value);
              s3out.write(rawJson);
              s3out.write(LINE_SEPARATOR_BYTES);
            } else {
              writer.writeObject(value);
              writer.writeRaw(LINE_SEPARATOR);
            }
          } catch (IOException e) {
            throw new ConnectException(e);
          }
        }

        @Override
        public void commit() {
          try {
            // Flush is required here, because closing the writer will close the underlying S3 output stream before
            // committing any data to S3.
            writer.flush();
            s3out.commit();
            writer.close();
          } catch (IOException e) {
            throw new ConnectException(e);
          }
        }

        @Override
        public void close() {
          try {
            writer.close();
          } catch (IOException e) {
            throw new ConnectException(e);
          }
        }
      };
    } catch (IOException e) {
      throw new ConnectException(e);
    }
  }
}
