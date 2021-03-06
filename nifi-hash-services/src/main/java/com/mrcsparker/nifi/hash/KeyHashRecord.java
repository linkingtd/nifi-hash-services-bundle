package com.mrcsparker.nifi.hash;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.Validator;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.record.path.FieldValue;
import org.apache.nifi.record.path.RecordPath;
import org.apache.nifi.record.path.RecordPathResult;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.*;
import org.apache.nifi.serialization.record.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"update", "hash", "record", "generic", "schema", "json", "csv", "avro", "log", "logs", "freeform", "text"})
@CapabilityDescription("Updates the contents of a FlowFile that contains Record-oriented data (i.e., data that can be read via a RecordReader and written by a RecordWriter). "
        + "This Processor requires that at least one user-defined Property be added. The name of the Property should indicate a RecordPath that determines the field that should "
        + "be updated. The value of the Property is either a replacement value (optionally making use of the Expression Language) or is itself a RecordPath that extracts a value from "
        + "the Record. Whether the Property value is determined to be a RecordPath or a literal value depends on the configuration of the <Replacement Value Strategy> Property.")

public class KeyHashRecord extends AbstractRecordProcessor {

    static final Logger LOG = LoggerFactory.getLogger(KeyHashRecord.class);

    static final PropertyDescriptor HASH_KEY = new PropertyDescriptor.Builder()
            .name("key")
            .displayName("Hash Key")
            .description("Hash Key")
            .required(true)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    static final PropertyDescriptor HASH_NAME = new PropertyDescriptor.Builder()
            .name("hash-name")
            .displayName("Hash Name")
            .description("Name for the hash attribute in the returned data structure")
            .required(true)
            .sensitive(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue("hash")
            .build();

    static final PropertyDescriptor PLAINTEXT_NAME = new PropertyDescriptor.Builder()
            .name("plaintext-name")
            .displayName("Plaintext Name")
            .description("Name for the plaintext attribute in the returned data structure")
            .required(true)
            .sensitive(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue("plaintext")
            .build();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(RECORD_READER);
        properties.add(RECORD_WRITER);
        properties.add(HASH_KEY);
        properties.add(HASH_NAME);
        properties.add(PLAINTEXT_NAME);
        properties.add(HashUtils.HASH_ALGORITHM);

        return properties;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .description("Specifies the value to use to replace fields in the record that match the RecordPath: " + propertyDescriptorName)
                .required(false)
                .dynamic(true)
                .addValidator(Validator.VALID)
                .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
                .build();
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final boolean containsDynamic = validationContext.getProperties().keySet().stream()
                .anyMatch(PropertyDescriptor::isDynamic);

        if (containsDynamic) {
            return Collections.emptyList();
        }

        return Collections.singleton(new ValidationResult.Builder()
                .subject("User-defined Properties")
                .valid(false)
                .explanation("At least one RecordPath must be specified")
                .build());
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final RecordReaderFactory readerFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);
        final RecordSetWriterFactory writerFactory = context.getProperty(RECORD_WRITER).asControllerService(RecordSetWriterFactory.class);

        final Map<String, String> attributes = new HashMap<>();
        final AtomicInteger recordCount = new AtomicInteger();

        final FlowFile original = flowFile;
        final Map<String, String> originalAttributes = flowFile.getAttributes();
        try {
            flowFile = session.write(flowFile, (in, out) -> {

                try (final RecordReader reader = readerFactory.createRecordReader(originalAttributes, in, original.getSize(), getLogger())) {

                    final RecordSchema writeSchema = writerFactory.getSchema(originalAttributes, reader.getSchema());
                    try (final RecordSetWriter writer = writerFactory.createWriter(getLogger(), writeSchema, out, originalAttributes)) {
                        writer.beginRecordSet();

                        Record record;
                        while ((record = reader.nextRecord()) != null) {
                            final List<Record> processed = processRecords(record, writeSchema, original, context);
                            for (Record r : processed) {
                                writer.write(r);
                            }
                        }

                        final WriteResult writeResult = writer.finishRecordSet();
                        attributes.put("record.count", String.valueOf(writeResult.getRecordCount()));
                        attributes.put(CoreAttributes.MIME_TYPE.key(), writer.getMimeType());
                        attributes.putAll(writeResult.getAttributes());
                        recordCount.set(writeResult.getRecordCount());
                    }
                } catch (final SchemaNotFoundException e) {
                    throw new ProcessException(e.getLocalizedMessage(), e);
                } catch (final MalformedRecordException e) {
                    throw new ProcessException("Could not parse incoming data", e);
                }
            });
        } catch (final Exception e) {
            getLogger().error("Failed to process {}; will route to failure", new Object[] {flowFile, e});
            session.transfer(flowFile, REL_FAILURE);
            return;
        }

        flowFile = session.putAllAttributes(flowFile, attributes);
        session.transfer(flowFile, REL_SUCCESS);

        final int count = recordCount.get();
        session.adjustCounter("Records Processed", count, false);
        getLogger().info("Successfully converted {} records for {}", new Object[] {count, flowFile});
    }

    private List<Record> processRecords(Record record, RecordSchema writeSchema, FlowFile flowFile, ProcessContext context) {

        final String hashName = context.getProperty(HASH_NAME).getValue();
        final String plaintextName = context.getProperty(PLAINTEXT_NAME).getValue();

        String hashKey = context.getProperty(HASH_KEY).getValue();
        String hashAlgorithm = context.getProperty(HashUtils.HASH_ALGORITHM).getValue();
        if (hashAlgorithm.isEmpty()) {
            hashAlgorithm = HashUtils.HASH_SHA256.getValue();
        }

        List<Record> records = new ArrayList<>();

        for (final String recordPathText : recordPaths) {

            final String replacementValue = context.getProperty(recordPathText).evaluateAttributeExpressions(flowFile).getValue();
            final RecordPath replacementRecordPath = recordPathCache.getCompiled(replacementValue);

            final RecordPathResult replacementResult = replacementRecordPath.evaluate(record);
            final List<FieldValue> selectedFields = replacementResult.getSelectedFields().collect(Collectors.toList());

            for (FieldValue selectedField : selectedFields) {
                final List<RecordField> fields = new ArrayList<>();
                fields.add(new RecordField(hashName, RecordFieldType.STRING.getDataType()));
                fields.add(new RecordField(plaintextName, RecordFieldType.STRING.getDataType()));

                final RecordSchema schema = new SimpleRecordSchema(fields);
                final Record newRecord = new MapRecord(schema, new HashMap<>());

                if (selectedField.getValue() != null && !StringUtils.isEmpty(selectedField.getValue().toString())) {
                    newRecord.setValue(hashName, HashUtils.getHash(hashAlgorithm, hashKey, selectedField.getValue().toString()));
                    newRecord.setValue(plaintextName, selectedField.getValue().toString());

                    records.add(newRecord);
                }
            }
        }
        return records;
    }
}
