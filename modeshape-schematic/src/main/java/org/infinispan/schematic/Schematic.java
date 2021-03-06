/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.infinispan.schematic;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.infinispan.Cache;
import org.infinispan.commons.marshall.AbstractExternalizer;
import org.infinispan.commons.marshall.AdvancedExternalizer;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.manager.CacheContainer;
import org.infinispan.schematic.document.Array;
import org.infinispan.schematic.document.Binary;
import org.infinispan.schematic.document.Changes;
import org.infinispan.schematic.document.Code;
import org.infinispan.schematic.document.Document;
import org.infinispan.schematic.document.Editor;
import org.infinispan.schematic.document.MaxKey;
import org.infinispan.schematic.document.MinKey;
import org.infinispan.schematic.document.Null;
import org.infinispan.schematic.document.ObjectId;
import org.infinispan.schematic.document.Symbol;
import org.infinispan.schematic.document.Timestamp;
import org.infinispan.schematic.internal.CacheSchematicDb;
import org.infinispan.schematic.internal.InMemorySchemaLibrary;
import org.infinispan.schematic.internal.SchematicEntryDelta;
import org.infinispan.schematic.internal.SchematicEntryLiteral;
import org.infinispan.schematic.internal.SchematicEntryWholeDelta;
import org.infinispan.schematic.internal.SchematicExternalizer;
import org.infinispan.schematic.internal.delta.AddValueIfAbsentOperation;
import org.infinispan.schematic.internal.delta.AddValueOperation;
import org.infinispan.schematic.internal.delta.ClearOperation;
import org.infinispan.schematic.internal.delta.DocumentObserver;
import org.infinispan.schematic.internal.delta.Operation;
import org.infinispan.schematic.internal.delta.PutIfAbsentOperation;
import org.infinispan.schematic.internal.delta.PutOperation;
import org.infinispan.schematic.internal.delta.RemoveAllValuesOperation;
import org.infinispan.schematic.internal.delta.RemoveAtIndexOperation;
import org.infinispan.schematic.internal.delta.RemoveOperation;
import org.infinispan.schematic.internal.delta.RemoveValueOperation;
import org.infinispan.schematic.internal.delta.RetainAllValuesOperation;
import org.infinispan.schematic.internal.delta.SetValueOperation;
import org.infinispan.schematic.internal.document.BasicArray;
import org.infinispan.schematic.internal.document.DocumentEditor;
import org.infinispan.schematic.internal.document.DocumentExternalizer;
import org.infinispan.schematic.internal.document.MutableDocument;
import org.infinispan.schematic.internal.document.ObservableDocumentEditor;
import org.infinispan.schematic.internal.document.Paths;
import org.infinispan.schematic.internal.marshall.Ids;

public class Schematic extends DocumentFactory {

    public static interface ContentTypes {
        public static final String BINARY = "application/octet-stream";
        public static final String JSON = "application/json";
        public static final String BSON = "application/bson";
        public static final String JSON_SCHEMA = "application/schema+json";
    }

    /**
     * Get the {@link SchematicDb} instance given the cache name and container.
     * 
     * @param cacheContainer the container for the named cache; may not be null
     * @param cacheName the name of the cache; may not be null
     * @return the schematic database instance; never null
     */
    public static SchematicDb get( CacheContainer cacheContainer,
                                   String cacheName ) {
        Cache<String, SchematicEntry> cache = cacheContainer.getCache(cacheName);
        return new CacheSchematicDb(cache);
    }

    /**
     * Obtain an editor for the supplied document. The editor allows the caller to make changes to the document and to obtain
     * these changes as a {@link Changes serializable memento} that can be applied to another document.
     * 
     * @param document the document to be edited
     * @param clone true if the editor should operate against a clone of the document, or false if it should operate against the
     *        supplied document
     * @return the editor for the document
     */
    public static Editor editDocument( Document document,
                                       boolean clone ) {
        if (clone) {
            document = document.clone();
        }
        final List<Operation> operations = new LinkedList<Operation>();
        final DocumentObserver observer = new DocumentObserver() {
            @Override
            public void addOperation( Operation o ) {
                if (o != null) {
                    operations.add(o);
                }
            }
        };
        MutableDocument mutable = null;
        if (document instanceof MutableDocument) mutable = (MutableDocument)document;
        else if (document instanceof DocumentEditor) mutable = ((DocumentEditor)document).asMutableDocument();
        return new EditorImpl(mutable, observer, operations);
    }

    protected static class EditorImpl extends ObservableDocumentEditor implements Editor {
        private static final long serialVersionUID = 1L;
        private final List<Operation> operations;

        public EditorImpl( MutableDocument document,
                           DocumentObserver observer,
                           List<Operation> operations ) {
            super(document, Paths.rootPath(), observer, null);
            this.operations = operations;
        }

        @Override
        public Changes getChanges() {
            return new DocumentChanges(operations);
        }

        @Override
        public void apply( Changes changes ) {
            apply(changes.clone(), null);
        }

        private final static Array.Entry newEntry( int index,
                                                   Object value ) {
            return new BasicArray.BasicEntry(index, value);
        }

        @Override
        public void apply( Changes changes,
                           Observer observer ) {
            if (changes.isEmpty()) {
                return;
            }
            MutableDocument mutable = asMutableDocument();
            for (Operation operation : (DocumentChanges)changes) {
                operation.replay(mutable);
                if (observer != null) {
                    if (operation instanceof SetValueOperation) {
                        SetValueOperation op = (SetValueOperation)operation;
                        observer.setArrayValue(op.getParentPath(), newEntry(op.getIndex(), op.getValue()));
                    } else if (operation instanceof AddValueOperation) {
                        AddValueOperation op = (AddValueOperation)operation;
                        if (op.getActualIndex() != -1) {
                            observer.addArrayValue(op.getParentPath(), newEntry(op.getActualIndex(), op.getValue()));
                        }
                    } else if (operation instanceof AddValueIfAbsentOperation) {
                        AddValueIfAbsentOperation op = (AddValueIfAbsentOperation)operation;
                        if (op.isAdded()) {
                            observer.addArrayValue(op.getParentPath(), newEntry(op.getIndex(), op.getValue()));
                        }
                    } else if (operation instanceof RemoveValueOperation) {
                        RemoveValueOperation op = (RemoveValueOperation)operation;
                        if (op.getActualIndex() != -1) {
                            observer.removeArrayValue(op.getParentPath(), newEntry(op.getActualIndex(), op.getRemovedValue()));
                        }
                    } else if (operation instanceof RemoveAtIndexOperation) {
                        RemoveAtIndexOperation op = (RemoveAtIndexOperation)operation;
                        observer.removeArrayValue(op.getParentPath(), newEntry(op.getIndex(), op.getRemovedValue()));
                    } else if (operation instanceof RetainAllValuesOperation) {
                        RetainAllValuesOperation op = (RetainAllValuesOperation)operation;
                        for (Array.Entry entry : op.getRemovedEntries()) {
                            observer.removeArrayValue(op.getParentPath(), entry);
                        }
                    } else if (operation instanceof RemoveAllValuesOperation) {
                        RemoveAllValuesOperation op = (RemoveAllValuesOperation)operation;
                        for (Array.Entry entry : op.getRemovedEntries()) {
                            observer.removeArrayValue(op.getParentPath(), entry);
                        }
                    } else if (operation instanceof ClearOperation) {
                        ClearOperation op = (ClearOperation)operation;
                        observer.clear(op.getParentPath());
                    } else if (operation instanceof PutOperation) {
                        PutOperation op = (PutOperation)operation;
                        observer.put(op.getParentPath(), op.getFieldName(), op.getNewValue());
                    } else if (operation instanceof PutIfAbsentOperation) {
                        PutIfAbsentOperation op = (PutIfAbsentOperation)operation;
                        if (op.isApplied()) {
                            observer.put(op.getParentPath(), op.getFieldName(), op.getNewValue());
                        }
                    } else if (operation instanceof RemoveOperation) {
                        RemoveOperation op = (RemoveOperation)operation;
                        if (op.isRemoved()) {
                            observer.remove(op.getParentPath(), op.getFieldName());
                        }
                    }
                }
            }
        }
    }

    protected static class DocumentChanges implements Changes, Iterable<Operation> {

        private final List<Operation> operations;

        protected DocumentChanges( List<Operation> operations ) {
            this.operations = operations;
        }

        @Override
        public DocumentChanges clone() {
            List<Operation> newOps = new ArrayList<Operation>(operations.size());
            for (Operation operation : operations) {
                newOps.add(operation.clone());
            }
            return new DocumentChanges(newOps);
        }

        @Override
        public Iterator<Operation> iterator() {
            return operations.iterator();
        }

        @Override
        public boolean isEmpty() {
            return operations.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            Iterator<Operation> iter = operations.iterator();
            if (iter.hasNext()) {
                sb.append(iter.next());
                while (iter.hasNext()) {
                    sb.append("\n").append(iter.next());
                }
            }
            return sb.toString();
        }

        public static class Externalizer extends AbstractExternalizer<DocumentChanges> {
            /** The serialVersionUID */
            private static final long serialVersionUID = 1L;

            @SuppressWarnings( "synthetic-access" )
            @Override
            public void writeObject( ObjectOutput output,
                                     DocumentChanges changes ) throws IOException {
                output.writeObject(changes.operations);
            }

            @Override
            public DocumentChanges readObject( ObjectInput input ) throws IOException, ClassNotFoundException {
                @SuppressWarnings( "unchecked" )
                List<Operation> operations = (List<Operation>)input.readObject();
                return new DocumentChanges(operations);
            }

            @Override
            public Integer getId() {
                return Ids.SCHEMATIC_DOCUMENT_CHANGES;
            }

            @SuppressWarnings( "unchecked" )
            @Override
            public Set<Class<? extends DocumentChanges>> getTypeClasses() {
                return Util.<Class<? extends DocumentChanges>>asSet(DocumentChanges.class);
            }
        }
    }

    /**
     * Create an in-memory schema library.
     * 
     * @return the empty, in-memory schema library
     */
    public static SchemaLibrary createSchemaLibrary() {
        return new InMemorySchemaLibrary("In-memory schema library");
    }

    /**
     * Create an in-memory schema library.
     * 
     * @param name the name of the library; may be null if a default name is to be used
     * @return the empty, in-memory schema library
     */
    public static SchemaLibrary createSchemaLibrary( String name ) {
        return new InMemorySchemaLibrary(name != null ? name : "In-memory schema library");
    }

    /**
     * Get the set of {@link org.infinispan.commons.marshall.Externalizer} implementations that are used by Schematic.
     * These need to be registered with the {@link GlobalConfiguration}:
     *
     * @return the list of externalizer
     */
    @SuppressWarnings( "unchecked" )
    public static AdvancedExternalizer<Object>[] externalizers() {
        return EXTERNALIZERS.toArray(new AdvancedExternalizer[EXTERNALIZERS.size()]);
    }

    /**
     * Get the complete set of {@link AdvancedExternalizer} implementations. Note that this does not include
     * {@link org.infinispan.commons.marshall.Externalizer} implementations that are not {@link AdvancedExternalizer}s.
     * 
     * @return immutable set of {@link AdvancedExternalizer} implementations.
     */
    public static Set<? extends AdvancedExternalizer<?>> externalizerSet() {
        return EXTERNALIZERS;
    }

    private static final Set<AdvancedExternalizer<?>> EXTERNALIZERS;

    static {
        Set<SchematicExternalizer<?>> externalizers = new HashSet<SchematicExternalizer<?>>();

        // SchematicDb values ...
        externalizers.add(new SchematicEntryLiteral.Externalizer());
        externalizers.add(new SchematicEntryDelta.Externalizer());
        externalizers.add(new SchematicEntryWholeDelta.Externalizer());

        // Documents ...
        externalizers.add(new DocumentExternalizer()); // BasicDocument and BasicArray
        externalizers.add(new Binary.Externalizer());
        externalizers.add(new Code.Externalizer()); // both Code and CodeWithScope
        externalizers.add(new MaxKey.Externalizer());
        externalizers.add(new MinKey.Externalizer());
        externalizers.add(new Null.Externalizer());
        externalizers.add(new ObjectId.Externalizer());
        externalizers.add(new Symbol.Externalizer());
        externalizers.add(new Timestamp.Externalizer());
        externalizers.add(new Paths.Externalizer());

        // Operations ...
        externalizers.add(new AddValueIfAbsentOperation.Externalizer());
        externalizers.add(new AddValueOperation.Externalizer());
        externalizers.add(new ClearOperation.Externalizer());
        externalizers.add(new PutOperation.Externalizer());
        externalizers.add(new PutIfAbsentOperation.Externalizer());
        externalizers.add(new RemoveAllValuesOperation.Externalizer());
        externalizers.add(new RemoveAtIndexOperation.Externalizer());
        externalizers.add(new RemoveOperation.Externalizer());
        externalizers.add(new RemoveValueOperation.Externalizer());
        externalizers.add(new RetainAllValuesOperation.Externalizer());
        externalizers.add(new SetValueOperation.Externalizer());

        // Add only those that are advanced ...
        Set<AdvancedExternalizer<?>> advancedExternalizers = new HashSet<AdvancedExternalizer<?>>();
        for (SchematicExternalizer<?> externalizer : externalizers) {
            if (externalizer instanceof AdvancedExternalizer) {
                advancedExternalizers.add((AdvancedExternalizer<?>)externalizer);
            }
        }
        EXTERNALIZERS = Collections.unmodifiableSet(advancedExternalizers);
    }
}
