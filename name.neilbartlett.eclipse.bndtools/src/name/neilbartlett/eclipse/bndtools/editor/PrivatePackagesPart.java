/*******************************************************************************
 * Copyright (c) 2010 Neil Bartlett.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Neil Bartlett - initial API and implementation
 *******************************************************************************/
package name.neilbartlett.eclipse.bndtools.editor;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import name.neilbartlett.eclipse.bndtools.editor.model.BndEditModel;
import name.neilbartlett.eclipse.bndtools.internal.pkgselection.IPackageFilter;
import name.neilbartlett.eclipse.bndtools.internal.pkgselection.JavaSearchScopePackageLister;
import name.neilbartlett.eclipse.bndtools.internal.pkgselection.PackageSelectionDialog;
import name.neilbartlett.eclipse.bndtools.utils.PackageDropAdapter;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.SectionPart;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.forms.widgets.Section;
import org.eclipse.ui.part.ResourceTransfer;

import aQute.lib.osgi.Constants;

public class PrivatePackagesPart extends SectionPart implements PropertyChangeListener {

	private BndEditModel model;
	private List<String> packages = new ArrayList<String>();
	
	private Table table;
	private TableViewer viewer;
	private Button btnAdd;
	private Button btnRemove;

	public PrivatePackagesPart(Composite parent, FormToolkit toolkit, int style) {
		super(parent, toolkit, style);
		createSection(getSection(), toolkit);
	}

	void createSection(Section section, FormToolkit toolkit) {
		section.setText("Private Packages");
		section.setDescription("The listed packages will be included in the bundle but not exported.");
		
		Composite composite = toolkit.createComposite(section);
		section.setClient(composite);
		
		table = toolkit.createTable(composite, SWT.FULL_SELECTION | SWT.MULTI);
		table.setHeaderVisible(false);
		table.setLinesVisible(true);
		
		TableColumn col;
		col = new TableColumn(table, SWT.NONE);
		col.setText("Package");
		col.setWidth(300);

		viewer = new TableViewer(table);
		viewer.setContentProvider(new ArrayContentProvider());
		viewer.setLabelProvider(new PrivatePackageTableLabelProvider());
		
		btnAdd = toolkit.createButton(composite, "Add", SWT.PUSH);
		btnRemove = toolkit.createButton(composite, "Remove", SWT.PUSH);
		btnRemove.setEnabled(false);
		
		// Listeners
		viewer.addSelectionChangedListener(new ISelectionChangedListener() {
			public void selectionChanged(SelectionChangedEvent event) {
				btnRemove.setEnabled(!viewer.getSelection().isEmpty());
			}
		});
		viewer.addDropSupport(DND.DROP_COPY | DND.DROP_MOVE, new Transfer[] { TextTransfer.getInstance(), ResourceTransfer.getInstance() }, new PackageDropAdapter<String>(viewer) {
			@Override
			protected String createNewEntry(String packageName) {
				return packageName;
			}
			@Override
			protected void addRows(int index, Collection<String> rows) {
				if(index == -1) {
					packages.addAll(rows);
					viewer.add(rows.toArray());
				} else {
					packages.addAll(index, rows);
					viewer.refresh();
				}
				viewer.setSelection(new StructuredSelection(rows));
				markDirty();
			}
			@Override
			protected int indexOf(Object object) {
				// TODO Auto-generated method stub
				return 0;
			}
		});
		btnAdd.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				doAddPackages();
			}
		});
		btnRemove.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				doRemovePackages();
			}
		});
		
		// Layout
		GridLayout layout = new GridLayout(2, false);
		composite.setLayout(layout);
		
		GridData gd;
		
		gd = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 3);
		gd.widthHint = 300;
		gd.heightHint = 100;
		table.setLayoutData(gd);
		
		btnAdd.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		btnRemove.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
	}
	
	private void doAddPackages() {
		// Prepare the exclusion list based on existing private packages
		final Set<String> packageNameSet = new HashSet<String>(packages);
		
		// Create a filter from the exclusion list and packages matching "java.*", which must not be included in a bundle
		IPackageFilter filter = new IPackageFilter() {
			public boolean select(String packageName) {
				return !packageName.equals("java") && !packageName.startsWith("java.") && !packageNameSet.contains(packageName);
			}
		};
		IFormPage page = (IFormPage) getManagedForm().getContainer();
		IWorkbenchWindow window = page.getEditorSite().getWorkbenchWindow();
		
		// Prepare the package lister from the Java project
		IJavaProject javaProject = getJavaProject();
		if(javaProject == null) {
			MessageDialog.openError(btnAdd.getShell(), "Error", "Cannot add packages: unable to find a Java project associated with the editor input.");
			return;
		}
		IJavaSearchScope searchScope = SearchEngine.createJavaSearchScope(new IJavaElement[] { javaProject });
		JavaSearchScopePackageLister packageLister = new JavaSearchScopePackageLister(searchScope, window);
		
		// Create and open the dialog
		PackageSelectionDialog dialog =  new PackageSelectionDialog(btnAdd.getShell(), packageLister, filter, "Select new packages to include in the bundle.");
		dialog.setSourceOnly(true);
		dialog.setMultipleSelection(true);
		if(dialog.open() == Window.OK) {
			Object[] results = dialog.getResult();
			List<String> added = new LinkedList<String>();
			
			// Select the results
			for (Object result : results) {
				String newPackageName = (String) result;
				if(packages.add(newPackageName)) {
					added.add(newPackageName);
				}
			}
			
			// Update the model and view
			if(!added.isEmpty()) {
				markDirty();
			}
		}
	}
	
	private void doRemovePackages() {
		IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
		if(!selection.isEmpty()) {
			Iterator<?> elements = selection.iterator();
			List<Object> removed = new LinkedList<Object>();
			while(elements.hasNext()) {
				Object pkg = elements.next();
				if(packages.remove(pkg))
					removed.add(pkg);
			}
			
			if(!removed.isEmpty()) {
				viewer.remove(removed.toArray(new String[removed.size()]));
				markDirty();
			}
		}
	}
	
	@Override
	public void commit(boolean onSave) {
		super.commit(onSave);
		model.setPrivatePackages(packages);
	}
	
	@Override
	public void refresh() {
		List<String> tmp = model.getPrivatePackages();
		if(tmp != null)
			packages = new ArrayList<String>(tmp);
		else
			packages = new ArrayList<String>();
		viewer.setInput(packages);
		super.refresh();
	}
	
	@Override
	public void initialize(IManagedForm form) {
		super.initialize(form);
		
		model = (BndEditModel) form.getInput();
		model.addPropertyChangeListener(Constants.PRIVATE_PACKAGE, this);
	}
	
	@Override
	public void dispose() {
		super.dispose();
		if(this.model != null)
			this.model.removePropertyChangeListener(this);
	}

	public void propertyChange(PropertyChangeEvent evt) {
		IFormPage page = (IFormPage) getManagedForm().getContainer();
		if(page.isActive()) {
			refresh();
		} else {
			markStale();
		}
	}
	
	private IJavaProject getJavaProject() {
		IFormPage page = (IFormPage) getManagedForm().getContainer();
		IEditorInput input = page.getEditorInput();
		if(!IFileEditorInput.class.isInstance(input)) {
			return null;
		}
		IProject project = ((IFileEditorInput) input).getFile().getProject();
		return JavaCore.create(project);
	}
}