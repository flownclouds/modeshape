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
package org.modeshape.web.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.types.FormMethod;
import com.smartgwt.client.util.SC;
import com.smartgwt.client.widgets.Canvas;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.events.ClickEvent;
import com.smartgwt.client.widgets.events.ClickHandler;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 *
 * @author kulikov
 */
public class AdminView extends View {

    private Console console;
    private BackupDialog backupDialog = new BackupDialog(this);
    private RestoreDialog restoreDialog = new RestoreDialog(this);

    private DynamicForm form = new DynamicForm();
    
    public AdminView(Console console, final JcrServiceAsync jcrService, ViewPort viewPort) {
        super(viewPort, null);
        this.console = console;

        addMember(new BackupControl());
        addMember(new RestoreControl());
        addMember(new DownloadControl());
        addMember(form);
    }

    public void backup(String name) {
        console.jcrService.backup(console.repository(), name, new AsyncCallback<Object>() {
            @Override
            public void onFailure(Throwable caught) {
                SC.say(caught.getMessage());
            }

            @Override
            public void onSuccess(Object result) {
                SC.say("Complete");
            }
        });
    }

    public void restore(String name) {
        console.jcrService.restore(console.repository(), name, new AsyncCallback<Object>() {
            @Override
            public void onFailure(Throwable caught) {
                SC.say(caught.getMessage());
            }

            @Override
            public void onSuccess(Object result) {
                SC.say("Complete");
            }
        });
    }

    private void backupAndDownload() {
        console.jcrService.backup(console.contents().repository(), "zzz", new AsyncCallback<Object>() {
            @Override
            public void onFailure(Throwable caught) {
                SC.say(caught.getMessage());
            }

            @Override
            public void onSuccess(Object result) {
                form.setAction(GWT.getModuleBaseForStaticFiles() + "backup/do?file=zzz");
                form.setMethod(FormMethod.GET);
                form.submitForm();
            }
        });
    }

    private class BackupControl extends VLayout {

        public BackupControl() {
            super();
            setStyleName("admin-control");

            Label label = new Label("Backup");
            label.setIcon("icons/data.png");
            label.setStyleName("button-label");
            label.setHeight(25);
            label.addClickHandler(new ClickHandler() {
                @Override
                public void onClick(ClickEvent event) {
                    backupDialog.showModal();
                }
            });

            Canvas text = new Canvas();
            text.setAutoHeight();
            text.setContents("Create backups of an entire repository (even when "
                    + "the repository is in use)"
                    + "This works regardless of where the repository content "
                    + "is persisted.");

            addMember(label);
            addMember(text);
        }
    }

    private class RestoreControl extends VLayout {

        public RestoreControl() {
            super();
            setStyleName("admin-control");

            Label label = new Label("Restore");
            label.setIcon("icons/documents.png");
            label.setStyleName("button-label");
            label.setHeight(25);
            label.addClickHandler(new ClickHandler() {
                @Override
                public void onClick(ClickEvent event) {
                    restoreDialog.showModal();
                }
            });

            Canvas text = new Canvas();
            text.setAutoHeight();
            text.setContents("Once you have a complete backup on disk, you can "
                    + "then restore a repository back to the state captured "
                    + "within the backup. To do that, simply start a repository "
                    + "(or perhaps a new instance of a repository with a "
                    + "different configuration) and, before it’s used by "
                    + "any applications, load into the new repository all of "
                    + "the content in the backup. ");

            addMember(label);
            addMember(text);
        }
    }

    private class DownloadControl extends VLayout {

        public DownloadControl() {
            super();
            setStyleName("admin-control");

            Label label = new Label("Backup & Download");
            label.setStyleName("button-label");
            label.setHeight(25);
            label.setIcon("icons/data.png");
            label.addClickHandler(new ClickHandler() {
                @Override
                public void onClick(ClickEvent event) {
                    backupAndDownload();
                }
            });

            Canvas text = new Canvas();
            text.setAutoHeight();
            text.setContents("Create backups of an entire repository (even when "
                    + "the repository is in use) and download zip archive "
                    + "This works regardless of where the repository content "
                    + "is persisted.");


            addMember(label);
            addMember(text);
        }
    }
}
