/*
 * Copyright 2006-2019 ICEsoft Technologies Canada Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.icepdf.ri.common.views.annotations;

import org.icepdf.core.pobjects.PDate;
import org.icepdf.core.pobjects.Page;
import org.icepdf.core.pobjects.Reference;
import org.icepdf.core.pobjects.annotations.Annotation;
import org.icepdf.core.pobjects.annotations.MarkupAnnotation;
import org.icepdf.core.pobjects.annotations.PopupAnnotation;
import org.icepdf.core.pobjects.annotations.TextAnnotation;
import org.icepdf.core.util.PropertyConstants;
import org.icepdf.core.util.SystemProperties;
import org.icepdf.ri.common.ViewModel;
import org.icepdf.ri.common.tools.TextAnnotationHandler;
import org.icepdf.ri.common.utility.annotation.properties.FreeTextAnnotationPanel;
import org.icepdf.ri.common.views.*;
import org.icepdf.ri.common.views.annotations.summary.AnnotationSummaryBox;
import org.icepdf.ri.images.Images;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.icepdf.core.util.SystemProperties.INTERACTIVE_ANNOTATIONS;

/**
 * The PopupAnnotationComponent encapsulates a PopupAnnotation objects.  It
 * also provides basic editing of the parent MarkupAnnotation's review state:
 * accepted, rejected, cancelled, completed, none. The component can also add
 * replyTo text annotations as well as delete comments.
 * <br>
 * The PopupAnnotationComponent is slightly more complex then the other
 * annotations components.  Most annotations let the page pain the annotation
 * but in this case PopupAnnotationComponent paints itself along with controls
 * for editing, replying and deleting TextAnnotation comments.
 * appearance stream.
 * <br>
 * Font size can be changed via a wheel mouse or crt-0 (reset), ctr--(zoom out) and crt-+ (zoom in).
 *
 * @see FreeTextAnnotationPanel
 * @since 5.0
 */
@SuppressWarnings("serial")
public class PopupAnnotationComponent extends AbstractAnnotationComponent<PopupAnnotation>
        implements TreeSelectionListener, ActionListener, DocumentListener, PropertyChangeListener, MouseWheelListener,
        DropTargetListener, PageViewAnnotationComponent {

    public static final int DEFAULT_WIDTH = 215;
    public static final int DEFAULT_HEIGHT = 150;
    public static final Color backgroundColor = new Color(252, 253, 227);
    public static final Dimension BUTTON_SIZE = new Dimension(22, 22);

    // layouts constraint
    private GridBagConstraints constraints;

    protected Color popupBackgroundColor;

    protected MouseListener popupListener;

    protected JPanel commentPanel;
    protected JTextArea textArea;
    protected JLabel creationLabel;
    protected JLabel titleLabel;
    protected JButton minimizeButton;
    protected JToggleButton privateToggleButton;
    protected JTree commentTree;
    protected JScrollPane commentTreeScrollPane;
    protected MarkupAnnotation selectedMarkupAnnotation;

    protected MarkupGlueComponent markupGlueComponent;

    protected final boolean disableSpellCheck;

    protected AbstractPageViewComponent parentPageViewComponent;

    private final String userName = SystemProperties.USER_NAME;

    public PopupAnnotationComponent(PopupAnnotation annotation, DocumentViewController documentViewController,
                                    AbstractPageViewComponent pageViewComponent) {
        this(annotation, documentViewController, pageViewComponent, false);
    }

    public PopupAnnotationComponent(PopupAnnotation annotation, DocumentViewController documentViewController,
                                    AbstractPageViewComponent pageViewComponent, boolean disableSpellCheck) {
        super(annotation, documentViewController, pageViewComponent);

        isEditable = true;
        isRollover = false;
        isMovable = true;
        isResizable = true;
        isShowInvisibleBorder = false;
        this.disableSpellCheck = disableSpellCheck;

        try {
            annotation.init();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.fine("Popup annotation component instance creation was interrupted");
        }

        buildGUI();

        // setup font scaling binding
        addFontSizeBindings();

        boolean isVisible = annotation.isOpen();
        setVisible(isVisible);

        ((DocumentViewControllerImpl) documentViewController).addPropertyChangeListener(this);

        // add drag and drop listeners
        new DropTarget(this, // component
                DnDConstants.ACTION_COPY_OR_MOVE, // actions
                this); // DropTargetListener
    }

    public void setParentPageComponent(AbstractPageViewComponent pageViewComponent) {
        parentPageViewComponent = pageViewComponent;
    }

    protected Rectangle limitAnnotationPosition(int x, int y, int width, int height) {
        return new Rectangle(x, y, width, height);
    }

    /**
     * Refreshes the components bounds for the current page transformation.
     * Bounds are already in user space.
     */
    public void refreshDirtyBounds() {
        Page currentPage = pageViewComponent.getPage();
        DocumentViewModel documentViewModel = documentViewController.getDocumentViewModel();
        Rectangle annotationPageSpaceBounds = annotation.calculatePageSpaceRectangle(
                currentPage,
                documentViewModel.getPageBoundary(),
                documentViewModel.getViewRotation(),
                documentViewModel.getViewZoom());
        Rectangle pageBounds = parentPageViewComponent.getParent().getBounds();
        annotationPageSpaceBounds.x += pageBounds.x;
        annotationPageSpaceBounds.y += pageBounds.y;
        setBounds(annotationPageSpaceBounds);
        // updates font size for latest scale factor
        setTextAreaFontSize(annotation.getTextAreaFontsize());
        setHeaderLabelsFontSize(annotation.getHeaderLabelsFontSize());
    }

    /**
     * Refreshes/transforms the page space bounds back to user space.  This
     * must be done in order refresh the annotation user space rectangle after
     * UI manipulation, otherwise the annotation will be incorrectly located
     * on the next repaint.
     */
    public void refreshAnnotationRect() {
        Page currentPage = pageViewComponent.getPage();
        DocumentViewModel documentViewModel = documentViewController.getDocumentViewModel();
        AffineTransform at = currentPage.getToPageSpaceTransform(
                documentViewModel.getPageBoundary(),
                documentViewModel.getViewRotation(),
                documentViewModel.getViewZoom());
        Rectangle pageBounds = parentPageViewComponent.getParent().getBounds();
        Rectangle bounds = getBounds();
        bounds.x -= pageBounds.x;
        bounds.y -= pageBounds.y;
        Rectangle annotationPageSpaceBounds = Annotation.commonBoundsNormalization(new GeneralPath(bounds), at);
        annotation.syncBBoxToUserSpaceRectangle(annotationPageSpaceBounds);
    }

    @Override
    public void dispose() {
        super.dispose();
        ((DocumentViewControllerImpl) documentViewController).removePropertyChangeListener(this);
    }

    public void mouseMoved(MouseEvent me) {
        if (!(annotation.getFlagLocked() || annotation.getFlagReadOnly())) {
            ResizableBorder border = (ResizableBorder) getBorder();
            setCursor(Cursor.getPredefinedCursor(border.getCursor(me)));
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {

        // setup visual effect when the mouse button is pressed or held down
        // inside the active area of the annotation.
        isMousePressed = true;

        if (INTERACTIVE_ANNOTATIONS &&
                !annotation.getFlagReadOnly()) {
            initiateMouseMoved(e);
        }
        repaint();
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {

        // check to make sure the new bounds will be visible, if not we correct them
        // this reads, ugly, sorry...
        if (isMousePressed) {
            Rectangle currentBounds = getBounds();
            Dimension documentSize = ((JComponent) pageViewComponent.getParentDocumentView()).getSize();
            if (currentBounds.x != x || currentBounds.y != y) {
                if (x < 0) {
                    if (currentBounds.width != width) width += x;
                    x = 0;
                } else if (x + width > documentSize.width) {
                    x = documentSize.width - currentBounds.width;
                }
                if (y < 0) {
                    if (currentBounds.height != height) height += y;
                    y = 0;
                } else if (y + height > documentSize.height) {
                    y = documentSize.height - currentBounds.height;
                }
            }
            if (currentBounds.width != width || currentBounds.height != height) {
                // we have a resize, make sure the component is contained in the page.
                if (x + width > documentSize.width) {
                    width = documentSize.width - x;
                } else if (y + height > documentSize.height) {
                    height = documentSize.height - y;
                }
            }
        }
        super.setBounds(x, y, width, height);
    }

    @Override
    public void setBounds(Rectangle r) {
        setBounds(r.x, r.y, r.width, r.height);
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        // borderColor
        g.setColor(popupBackgroundColor);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(PopupAnnotation.BORDER_COLOR);
        g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
    }

    @Override
    public void setVisible(boolean aFlag) {
        super.setVisible(aFlag);
        // do a quick check to make sure the popup will be big enough to be visible
        Rectangle bounds = getBounds();
        if (bounds.width < DEFAULT_WIDTH || bounds.height < DEFAULT_HEIGHT) {
            setBounds(bounds.x, bounds.y, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }
        if (getParent() != null) getParent().repaint();
        if (aFlag) {
            textArea.requestFocusInWindow();
        }
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        super.mouseEntered(e);
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
    }

    private void buildGUI() {

        List<Annotation> annotations = pageViewComponent.getPage().getAnnotations();
        MarkupAnnotation parentAnnotation = annotation.getParent();

        // check first if there are anny annotation that point to this one as
        // an IRT.  If there aren't any then the selectedAnnotation is the parent
        // other wise we need to build out
        DefaultMutableTreeNode root =
                new DefaultMutableTreeNode("Root");
        boolean isIRT = buildCommentTree(parentAnnotation, annotations, root);
        commentTree = new JTree(root);
        commentTree.setRootVisible(true);
        commentTree.setExpandsSelectedPaths(true);
        commentTree.setShowsRootHandles(true);
        commentTree.setScrollsOnExpand(true);
        commentTree.setRootVisible(false);
        commentTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        commentTree.addTreeSelectionListener(this);
        // expand the tree
        refreshTree(commentTree);
        // set look and feel to match outline style
        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
        renderer.setOpenIcon(null);
        renderer.setClosedIcon(null);
        renderer.setLeafIcon(null);
        commentTree.setCellRenderer(renderer);
        commentTree.addMouseListener(new PopupTreeListener());
        commentTreeScrollPane = new JScrollPane(commentTree);
        // make sure the root node is selected by default.
        commentTree.setSelectionRow(0);

        // Set the
        selectedMarkupAnnotation = parentAnnotation;

        // try and make the popup the same colour as the annotations fill color
        popupBackgroundColor = backgroundColor;
        if (parentAnnotation != null && parentAnnotation.getColor() != null) {
            popupBackgroundColor = checkColor(parentAnnotation.getColor());
        }

        // minimize button

        minimizeButton = new JButton("  _  ");
        minimizeButton.addActionListener(this);
        minimizeButton.setToolTipText(messageBundle.getString(
                "viewer.annotation.popup.mimimize.tooltip.label"));
        minimizeButton.setContentAreaFilled(false);
        minimizeButton.setBorder(BorderFactory.createLineBorder(PopupAnnotation.BORDER_COLOR));
        minimizeButton.setBorderPainted(true);
        minimizeButton.setFocusPainted(false);
        minimizeButton.addActionListener(this);
        minimizeButton.setPreferredSize(BUTTON_SIZE);
        minimizeButton.setSize(BUTTON_SIZE);

        // lock button
        privateToggleButton = new JToggleButton();
        boolean isPrivate = annotation.getParent() != null &&
                annotation.getParent().getFlagPrivateContents();
        privateToggleButton.setToolTipText(messageBundle.getString(
                "viewer.utilityPane.markupAnnotation.view.publicToggleButton.tooltip.label"));
        privateToggleButton.setSelected(isPrivate);
        privateToggleButton.setFocusPainted(false);
        privateToggleButton.setPreferredSize(BUTTON_SIZE);
        privateToggleButton.setSize(BUTTON_SIZE);
        privateToggleButton.addActionListener(this);
        privateToggleButton.setContentAreaFilled(false);
        privateToggleButton.setBorder(BorderFactory.createLineBorder(PopupAnnotation.BORDER_COLOR));
        privateToggleButton.setBorderPainted(true);

        // text area edited the selected annotation markup contents.
        String contents = annotation.getParent() != null ?
                annotation.getParent().getContents() : "";
        textArea = new JTextArea(contents != null ? contents : "");
        textArea.setFont(new JLabel().getFont());
        textArea.setWrapStyleWord(true);
        textArea.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(PopupAnnotation.BORDER_COLOR),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)));

        textArea.setLineWrap(true);
        textArea.getDocument().addDocumentListener(this);
        if (!disableSpellCheck) {
            SpellCheckLoader.addSpellChecker(textArea);
        }

        // creation date
        creationLabel = new JLabel();
        refreshCreationLabel();
        // title, user name.
        String title = selectedMarkupAnnotation != null ?
                selectedMarkupAnnotation.getFormattedTitleText() : "";
        titleLabel = new JLabel(title);

        // Setup color appearance values.
        resetComponentColors();

        // main layout panel
        GridBagLayout layout = new GridBagLayout();
        commentPanel = new JPanel(layout);
        commentPanel.setBackground(popupBackgroundColor);
        this.setLayout(new GridBagLayout());
        /*
         * Build search GUI
         */
        constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(0, 0, 0, 0);
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        addGB(this, commentPanel, 0, 0, 1, 1);
        /*
         * Build search GUI
         */
        constraints.weighty = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(1, 1, 1, 1);

        // currently selected title
        constraints.fill = GridBagConstraints.EAST;
        constraints.weightx = 0;

        addGB(commentPanel, titleLabel, 0, 0, 1, 1);

        // add minimize button
        constraints.fill = GridBagConstraints.REMAINDER;
        constraints.weightx = 0;
        constraints.insets = new Insets(1, 1, 1, 1);
        // user that created the comment is the only one that can actually make it private.
        if (SystemProperties.PRIVATE_PROPERTY_ENABLED) {
            MarkupAnnotation markupAnnotation = annotation.getParent();
            if (markupAnnotation != null && userName.equals(markupAnnotation.getTitleText())) {
                addGB(commentPanel, privateToggleButton, 2, 0, 1, 1);
            }
        }
        constraints.insets = new Insets(1, 1, 1, 1);
        addGB(commentPanel, minimizeButton, 3, 0, 1, 1);

        // add comment tree if there are any IRT's
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(1, 1, 1, 1);
        constraints.weightx = 1.0;
        constraints.weighty = .6;
        commentTreeScrollPane.setVisible(isIRT);
        addGB(commentPanel, commentTreeScrollPane, 0, 1, 4, 1);

        // creation date of selected comment
        constraints.insets = new Insets(1, 1, 1, 1);
        constraints.weightx = 1.0;
        constraints.weighty = 0;
        constraints.fill = GridBagConstraints.EAST;
        addGB(commentPanel,
                creationLabel,
                0, 2, 1, 1);

        // add the text area
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(1, 1, 1, 1);
        constraints.weightx = 1.0;
        constraints.weighty = .4;
        addGB(commentPanel, textArea, 0, 3, 4, 1);

        // setup move on coloured background
        commentPanel.addMouseListener(this);
        commentPanel.addMouseMotionListener(this);

        // command test
        buildContextMenu();
    }

    public void focusTextArea() {
        textArea.requestFocusInWindow();
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    public void setBoundsRelativeToParent(int x, int y, AffineTransform pageInverseTransform) {
        Rectangle pageBounds = pageViewComponent.getParent().getBounds();
        // position the new popup on the icon center.
        Rectangle bBox2 = new Rectangle(x, y,
                (int) Math.abs(DEFAULT_WIDTH * pageInverseTransform.getScaleX()),
                (int) Math.abs(DEFAULT_HEIGHT * pageInverseTransform.getScaleY()));

        // add page offset
        bBox2.x += pageBounds.x;
        bBox2.y += pageBounds.y;
        // set the bounds and refresh the userSpace rectangle
        setBounds(bBox2);
        // resets user space rectangle to match bbox converted to page space
        // todo likely need to override for document level painting
        refreshAnnotationRect();
    }

    private void refreshTree(JTree tree) {
        ((DefaultTreeModel) (tree.getModel())).reload();
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    public void buildContextMenu() {
        //Create the popup menu.
        MarkupAnnotationComponent comp = (MarkupAnnotationComponent) getAnnotationParentComponent();
        contextMenu = new MarkupAnnotationPopupMenu(comp, documentViewController.getParentController(),
                getPageViewComponent(), false);
        // Add listener to components that can bring up popup menus.
        popupListener = new PopupListener(contextMenu);
        commentPanel.addMouseListener(popupListener);
    }

    public void replyToSelectedMarkupExecute() {
        // setup title message
        String annotationTitle = System.getProperty("user.name");

        // show the currently selected markup comment.
        setVisible(true);

        createNewTextAnnotation(
                annotationTitle,
                "",
                TextAnnotation.STATE_MODEL_REVIEW,
                TextAnnotation.STATE_REVIEW_NONE);
    }

    public AnnotationComponent getAnnotationParentComponent() {
        return findAnnotationComponent(annotation.getParent());
    }

    /**
     * Deletes the root annotation element or the selected tree node of the annotation popup view depending on the
     * the value of the deleteRoot parameter.
     *
     * @param deleteRoot true delete the entire annotation sub comments,  false delete just the selected node.
     */
    public void deleteSelectedMarkupExecute(boolean deleteRoot) {

        // show the currently selected markup comment.
        setVisible(true);

        // remove the annotation
        AnnotationComponent annotationComponent;
        if (deleteRoot) {
            annotationComponent = getAnnotationParentComponent();
        } else {
            annotationComponent = findAnnotationComponent(selectedMarkupAnnotation);
        }
        documentViewController.deleteAnnotation(annotationComponent);

        // check if any annotations have an IRT reference and delete
        // the markup component chain
        removeMarkupInReplyTo(selectedMarkupAnnotation.getPObjectReference());


        // rebuild the tree, which is easier then pruning at this point
        rebuildTree();
        commentPanel.revalidate();
        refreshPopupState();
    }

    public void setStatusSelectedMarkupExecute(String messageTitle, String messageBody, String status) {
        // setup title message
        Object[] argument = new Object[]{selectedMarkupAnnotation.getTitleText()};
        MessageFormat formatter = new MessageFormat(messageTitle);
        String title = formatter.format(argument);
        // setup content message
        argument = new Object[]{selectedMarkupAnnotation.getTitleText()};
        formatter = new MessageFormat(messageBody);
        String content = formatter.format(argument);
        createNewTextAnnotation(title, content,
                TextAnnotation.STATE_MODEL_REVIEW, status);
    }

    private void addFontSizeBindings() {

        addMouseWheelListener(this);

        InputMap inputMap = getInputMap(WHEN_FOCUSED);
        ActionMap actionMap = getActionMap();

        /// ctrl-- to increase font size.
        KeyStroke key = KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(key, "font-size-increase");
        actionMap.put("font-size-increase", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (hasFocus()) {
                    changeFontSize(1);
                }
            }
        });

        // ctrl-0 to default font size.
        key = KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(key, "font-size-default");
        actionMap.put("font-size-default", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (hasFocus()) {
                    setFontSize(new JLabel().getFont().getSize());
                }
            }
        });

        // ctrl-- to decrease font size.
        key = KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(key, "font-size-decrease");
        actionMap.put("font-size-decrease", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (hasFocus()) {
                    changeFontSize(-1);
                }
            }
        });
    }


    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (hasFocus() || textArea.hasFocus()) {
            if (findComponentAt(e.getPoint()) == textArea) {
                setTextAreaFontSize(annotation.getTextAreaFontsize() - e.getWheelRotation());
            } else {
                changeFontSize(-e.getWheelRotation());
            }
        }
    }

    public void actionPerformed(ActionEvent e) {
        Object source = e.getSource();
        if (source == null)
            return;
        // hide the window on minimize
        if (source == minimizeButton) {
            this.setVisible(false);
            annotation.setOpen(false);
            annotation.setModifiedDate(PDate.formatDateTime(new Date()));
            documentViewController.updateAnnotation(this);
        } else if (source == privateToggleButton) {
            boolean selected = privateToggleButton.isSelected();
            MarkupAnnotation markupAnnotation = annotation.getParent();
            if (markupAnnotation != null) {
                markupAnnotation.setFlag(Annotation.FLAG_PRIVATE_CONTENTS, selected);
                markupAnnotation.setModifiedDate(PDate.formatDateTime(new Date()));
                documentViewController.updateAnnotation(findAnnotationComponent(markupAnnotation));
                documentViewController.updateAnnotation(this);
            }
        }
    }

    public void showHidePopupAnnotations(boolean visible) {
        ArrayList<AbstractAnnotationComponent> annotationComponents =
                pageViewComponent.getAnnotationComponents();
        for (AnnotationComponent annotationComponent : annotationComponents) {
            if (annotationComponent instanceof PopupAnnotationComponent) {
                PopupAnnotationComponent popupAnnotationComponent = (PopupAnnotationComponent) annotationComponent;
                if (popupAnnotationComponent.getAnnotation() != null) {
                    PopupAnnotation popupAnnotation = popupAnnotationComponent.getAnnotation();
                    if (popupAnnotation.getParent() != null &&
                            popupAnnotation.getParent().getInReplyToAnnotation() == null) {
                        popupAnnotationComponent.setVisible(visible);
                        popupAnnotationComponent.getAnnotation().setOpen(visible);
                    }
                }
            }
        }
    }

    private void createNewTextAnnotation(String title, String content,
                                         String stateModel, String state) {
        // on reply we need to create a new textAnnotation/popup combo
        // and setup the IRT references for display
        TextAnnotation markupAnnotation =
                TextAnnotationHandler.createTextAnnotation(
                        documentViewController.getDocument().getPageTree().getLibrary(),
                        selectedMarkupAnnotation.getUserSpaceRectangle().getBounds(),
                        getToPageSpaceTransform());
        // set the private contents flag.
        ViewModel viewModel = documentViewController.getParentController().getViewModel();
        markupAnnotation.setFlag(Annotation.FLAG_PRIVATE_CONTENTS, !viewModel.getAnnotationPrivacy());
        markupAnnotation.setTitleText(title);
        markupAnnotation.setContents(content);
        markupAnnotation.setState(state);
        markupAnnotation.setStateModel(stateModel);
        markupAnnotation.setInReplyToAnnotation(selectedMarkupAnnotation);
        markupAnnotation.setColor(selectedMarkupAnnotation.getColor());

        // create the new text and popup annotations
//        PopupAnnotation popupAnnotation =
//                TextAnnotationHandler.createPopupAnnotation(
//                        documentViewModel.getDocument().getPageTree().getLibrary(),
//                        this.popupAnnotation.getUserSpaceRectangle().getBounds(),
//                        markupAnnotation, getToPageSpaceTransform());
//        popupAnnotation.setOpen(false);
//        addAnnotationComponent(popupAnnotation);

        // finally add the node as child to the selected node
        DefaultMutableTreeNode node = (DefaultMutableTreeNode)
                commentTree.getLastSelectedPathComponent();

        DefaultMutableTreeNode replyToNode =
                new MarkupAnnotationTreeNode(markupAnnotation);
        if (node == null) {
            node = ((DefaultMutableTreeNode) commentTree.getModel().getRoot()).getFirstLeaf();
        }
        node.insert(replyToNode, node.getChildCount());

        commentTree.expandRow(replyToNode.getDepth() - 1);
        selectedMarkupAnnotation = markupAnnotation;

        // reload the tree model
        refreshTree(commentTree);
        commentTree.setSelectionPath(new TreePath(replyToNode.getPath()));

        // finally check the view and make sure the treePanel is visible.
        commentTreeScrollPane.setVisible(true);
        commentPanel.revalidate();
        addAnnotationComponent(markupAnnotation);
    }

    public void insertUpdate(DocumentEvent e) {
        updateContent(e);
    }

    public void removeUpdate(DocumentEvent e) {
        updateContent(e);
    }

    public void changedUpdate(DocumentEvent e) {
        updateContent(e);
    }

    protected void updateContent(DocumentEvent e) {
        // get the next text and save it to the selected markup annotation.
        Document document = e.getDocument();
        try {
            if (document.getLength() > 0) {
                selectedMarkupAnnotation.setModifiedDate(PDate.formatDateTime(new Date()));
                selectedMarkupAnnotation.setContents(
                        document.getText(0, document.getLength()));
                documentViewController.updateAnnotation(getMarkupAnnotationComponent());
                // add them to the container, using absolute positioning.
                documentViewController.updateAnnotation(this);
            }
        } catch (BadLocationException ex) {
            logger.log(Level.FINE, "Error updating markup annotation content", ex);
        }
    }

    public void valueChanged(TreeSelectionEvent e) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode)
                commentTree.getLastSelectedPathComponent();

        // Nothing is selected.
        if (node == null)
            return;

        Object userObject = node.getUserObject();
        if (userObject instanceof MarkupAnnotation) {
            selectedMarkupAnnotation = (MarkupAnnotation) userObject;
            refreshPopupState();
        }
    }

    public void refreshPopupState() {
        if (textArea != null) {
            // update the private/public button.
            if (privateToggleButton.isVisible()) {
                privateToggleButton.setSelected(selectedMarkupAnnotation.getFlagPrivateContents());
            }
            // update the text
            textArea.getDocument().removeDocumentListener(this);
            textArea.setText(selectedMarkupAnnotation.getContents());
            textArea.getDocument().addDocumentListener(this);
        }
        refreshCreationLabel();
    }

    private void refreshCreationLabel() {
        if (selectedMarkupAnnotation != null && creationLabel != null) {
            creationLabel.setText(selectedMarkupAnnotation.getFormattedCreationDate(FormatStyle.MEDIUM));
        }
    }

    private void rebuildTree() {
        List<Annotation> annotations = pageViewComponent.getPage().getAnnotations();
        MarkupAnnotation parentAnnotation = annotation.getParent();

        // check first if there are anny annotation that point to this one as
        // an IRT.  If there aren't any then the selectedAnnotation is the parent
        // other wise we need to build out
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        boolean isIRT = buildCommentTree(parentAnnotation, annotations, root);
        commentTree.removeTreeSelectionListener(this);
        ((DefaultTreeModel) (commentTree.getModel())).setRoot(root);
        commentTree.addTreeSelectionListener(this);
        // reload the tree model
        commentTreeScrollPane.setVisible(isIRT);
        refreshTree(commentTree);
        if (!isIRT) {
            selectedMarkupAnnotation = parentAnnotation;
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (PropertyConstants.ANNOTATION_SUMMARY_UPDATED.equals(evt.getPropertyName())) {
            if (!(this instanceof AnnotationSummaryBox)) {
                AnnotationSummaryBox annotationSummaryBox = (AnnotationSummaryBox) evt.getNewValue();
                if (this.getAnnotation().equals(annotationSummaryBox.getAnnotation())) {
                    // update text
                    textArea.getDocument().removeDocumentListener(this);
                    textArea.setText(annotationSummaryBox.textArea.getText());
                    textArea.getDocument().addDocumentListener(this);
                    // update private state.
                    privateToggleButton.setSelected(annotationSummaryBox.privateToggleButton.isSelected());
                }
            }
        } else if (PropertyConstants.ANNOTATION_DELETED.equals(evt.getPropertyName())) {
            final AnnotationComponent ac = ((AnnotationComponent) evt.getOldValue());
            if (ac instanceof MarkupAnnotationComponent) {
                if (ac.getAnnotation() != null) {
                    if (containsRefs(Collections.singleton(ac.getAnnotation().getPObjectReference()))) {
                        rebuildTree();
                        commentPanel.revalidate();
                    }
                }
            }
        } else if (PropertyConstants.ANNOTATION_ADDED.equals(evt.getPropertyName())) {
            final AnnotationComponent ac = (AnnotationComponent) evt.getNewValue();
            if (ac instanceof MarkupAnnotationComponent) {
                if (ac.getAnnotation() != null) {
                    MarkupAnnotation annot = (MarkupAnnotation) ac.getAnnotation();
                    if (!containsRefs(Collections.singleton(annot.getPObjectReference()))) {
                        final Set<Reference> refs = new HashSet<>();
                        while (annot.isInReplyTo()) {
                            refs.add(annot.getInReplyToAnnotation().getPObjectReference());
                            annot = annot.getInReplyToAnnotation();
                        }
                        if (containsRefs(refs)) {
                            rebuildTree();
                            commentPanel.revalidate();
                        }
                    }
                }
            }
        } else if (PropertyConstants.ANNOTATION_UPDATED.equals(evt.getPropertyName())) {
            final AnnotationComponent ac = (AnnotationComponent) evt.getNewValue();
            if (ac instanceof MarkupAnnotationComponent) {
                if (ac.getAnnotation() != null) {
                    updateTreeColor(ac.getAnnotation());
                }
            }
        }
    }

    private void updateTreeColor(final Annotation annotation) {
        final Set<Annotation> treeAnnotations = new HashSet<>(getAllAnnotations());
        if (treeAnnotations.contains(annotation)) {
            final List<Annotation> toChange = treeAnnotations.stream()
                    .filter(a -> !a.getColor().equals(annotation.getColor())).collect(Collectors.toList());
            toChange.forEach(a -> {
                a.setColor(annotation.getColor());
                final AnnotationComponent ac = pageViewComponent.getComponentFor(a);
                if (ac != null) {
                    documentViewController.updateAnnotation(ac);
                } else {
                    logger.warning("Component not found for " + a);
                }
            });
            if (!toChange.isEmpty()) {
                refreshPopupState();
                commentPanel.revalidate();
            }
        }
    }

    private boolean containsRefs(final Set<Reference> refs) {
        if (commentTree != null) {
            return containsRefs(refs, commentTree.getModel().getRoot());
        } else {
            return false;
        }
    }

    private static boolean containsRefs(final Set<Reference> refs, final Object node) {
        if (node != null) {
            if (node instanceof TreeNode) {
                final TreeNode tn = (TreeNode) node;
                if (node instanceof MarkupAnnotationTreeNode) {
                    final Annotation annot = (MarkupAnnotation) ((MarkupAnnotationTreeNode) node).getUserObject();
                    if (refs != null && refs.contains(annot.getPObjectReference())) {
                        return true;
                    }
                }
                for (int i = 0; i < tn.getChildCount(); ++i) {
                    if (containsRefs(refs, tn.getChildAt(i))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public List<Annotation> getAllAnnotations() {
        if (commentTree != null) {
            return getAllAnnotations(commentTree.getModel().getRoot()).collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    private static Stream<Annotation> getAllAnnotations(final Object node) {
        if (node != null) {
            if (node instanceof TreeNode) {
                final TreeNode tn = (TreeNode) node;
                final boolean hasChildren = tn.getChildCount() > 0;
                if (node instanceof MarkupAnnotationTreeNode) {
                    final Annotation annot = (MarkupAnnotation) ((MarkupAnnotationTreeNode) node).getUserObject();
                    return hasChildren ? Stream.concat(Stream.of(annot), Collections.list(tn.children()).stream().flatMap(PopupAnnotationComponent::getAllAnnotations)) : Stream.of(annot);
                } else {
                    return hasChildren ? Collections.list(tn.children()).stream().flatMap(PopupAnnotationComponent::getAllAnnotations) : Stream.empty();
                }
            } else {
                return Stream.empty();
            }
        } else {
            return Stream.empty();
        }
    }

    public boolean isActive() {
        return false;
    }

    public MarkupAnnotationComponent getMarkupAnnotationComponent() {
        if (annotation != null) {
            MarkupAnnotation markupAnnotation = annotation.getParent();
            if (markupAnnotation != null) {
                // find the popup component
                ArrayList<AbstractAnnotationComponent> annotationComponents = pageViewComponent.getAnnotationComponents();
                Reference compReference;
                Reference markupReference = markupAnnotation.getPObjectReference();
                for (AnnotationComponent annotationComponent : annotationComponents) {
                    compReference = annotationComponent.getAnnotation().getPObjectReference();
                    // find the component and toggle it's visibility, null check just encase compRef is direct.
                    if (compReference != null && compReference.equals(markupReference)) {
                        if (annotationComponent instanceof MarkupAnnotationComponent) {
                            return ((MarkupAnnotationComponent) annotationComponent);
                        }
                        break;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Gridbag constructor helper
     *
     * @param panel     parent adding component too.
     * @param component component to add to grid
     * @param x         row
     * @param y         col
     * @param rowSpan   rowspane of field
     * @param colSpan   colspane of field.
     */
    private void addGB(JComponent panel, Component component,
                       int x, int y,
                       int rowSpan, int colSpan) {
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.gridwidth = rowSpan;
        constraints.gridheight = colSpan;
        panel.add(component, constraints);
    }

    private boolean buildCommentTree(MarkupAnnotation parentAnnotation,
                                     List<Annotation> annotations,
                                     DefaultMutableTreeNode root) {
        boolean foundIRT = checkForIRT(parentAnnotation, annotations);
        DefaultMutableTreeNode node = new MarkupAnnotationTreeNode(parentAnnotation);
        root.add(node);
        if (!foundIRT) {
            // simple test add a new node for the parent annotation.
            return false;
        } else {
            // find every IRT and add it to the tree.
            buildRecursiveCommentTree(node, annotations);
            return true;
        }

    }

    private void buildRecursiveCommentTree(DefaultMutableTreeNode root,
                                           List<Annotation> annotations) {
        MarkupAnnotation currentMarkup = (MarkupAnnotation) root.getUserObject();
        Reference reference = currentMarkup.getPObjectReference();
        for (Annotation annotation : annotations) {
            if (annotation instanceof MarkupAnnotation) {
                MarkupAnnotation markupAnnotation = (MarkupAnnotation) annotation;
                MarkupAnnotation inReplyToAnnotation =
                        markupAnnotation.getInReplyToAnnotation();
                if (inReplyToAnnotation != null &&
                        inReplyToAnnotation.getPObjectReference().equals(reference)) {
                    // found one no were to attach it to.
                    root.add(new MarkupAnnotationTreeNode(markupAnnotation));
                    selectedMarkupAnnotation = markupAnnotation;
                }
            }
        }
        int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            buildRecursiveCommentTree((DefaultMutableTreeNode) root.getChildAt(i),
                    annotations);
        }

    }

    private void removeMarkupInReplyTo(Reference reference) {
        if (reference != null) {
            ArrayList<AbstractAnnotationComponent> annotationComponents =
                    pageViewComponent.getAnnotationComponents();
            MarkupAnnotationComponent markupAnnotationComponent;
            MarkupAnnotation markupAnnotation;
            AnnotationComponent annotationComponent;
            for (int i = 0; i < annotationComponents.size(); i++) {
                annotationComponent = annotationComponents.get(i);
                if (annotationComponent instanceof MarkupAnnotationComponent) {
                    markupAnnotationComponent = (MarkupAnnotationComponent) annotationComponent;
                    markupAnnotation = (MarkupAnnotation) markupAnnotationComponent.getAnnotation();
                    if (markupAnnotation.getInReplyToAnnotation() != null &&
                            markupAnnotation.getInReplyToAnnotation()
                                    .getPObjectReference().equals(reference)) {
                        // recursive check if there are any object that refer
                        // to this IRT annotation being deleted.
                        removeMarkupInReplyTo(markupAnnotation.getPObjectReference());
                        documentViewController.deleteAnnotation(markupAnnotationComponent);
                    }
                }
            }
        }
    }

    private boolean checkForIRT(MarkupAnnotation parentAnnotation,
                                List<Annotation> annotations) {
        if (parentAnnotation != null && annotations != null) {
            Reference reference = parentAnnotation.getPObjectReference();
            for (Annotation annotation : annotations) {
                if (annotation instanceof MarkupAnnotation) {
                    MarkupAnnotation markupAnnotation = (MarkupAnnotation) annotation;
                    MarkupAnnotation inReplyToAnnotation =
                            markupAnnotation.getInReplyToAnnotation();
                    if (inReplyToAnnotation != null &&
                            inReplyToAnnotation.getPObjectReference().equals(reference))
                        return true;
                }
            }
        }
        return false;
    }

    private void addAnnotationComponent(Annotation annotation) {
        // draw them off screen
        Rectangle bBox = new Rectangle(-20, -20, 20, 20);
        // create the annotation object.
        MarkupAnnotationComponent comp = (MarkupAnnotationComponent)
                AnnotationComponentFactory.buildAnnotationComponent(
                        annotation, documentViewController, pageViewComponent);
        // set the bounds and refresh the userSpace rectangle
        comp.setBounds(bBox);
        // resets user space rectangle to match bbox converted to page space
        comp.refreshAnnotationRect();
        comp.setVisible(false);
        comp.getPopupAnnotationComponent().setVisible(false);

        // add them to the container, using absolute positioning.
        documentViewController.addNewAnnotation(comp);
    }

    protected AnnotationComponent findAnnotationComponent(Annotation annotation) {
        return pageViewComponent.getComponentFor(annotation);
    }

    protected void resetComponentColors() {
        Color contrastColor = annotation.calculateContrastHighLowColor(popupBackgroundColor.getRGB());
        minimizeButton.setForeground(contrastColor);
        minimizeButton.setBackground(popupBackgroundColor);
        minimizeButton.setBackground(popupBackgroundColor);
        privateToggleButton.setBackground(popupBackgroundColor);
        // lock icons.
        Icon lockedIcon = new ImageIcon(Images.get("lock_16.png"));
        Icon unlockedIcon = new ImageIcon(Images.get("unlock_16.png"));
        privateToggleButton.setIcon(unlockedIcon);
        privateToggleButton.setPressedIcon(null);
        privateToggleButton.setSelectedIcon(lockedIcon);
        privateToggleButton.setRolloverIcon(unlockedIcon);
        privateToggleButton.setRolloverSelectedIcon(lockedIcon);

        // text colors.
        titleLabel.setForeground(contrastColor);
        creationLabel.setForeground(contrastColor);
    }

    @Override
    public void resetAppearanceShapes() {
        MarkupAnnotation parentAnnotation = annotation.getParent();
        if (parentAnnotation.getColor() != null) {
            Color color = checkColor(parentAnnotation.getColor());
            popupBackgroundColor = color;
            minimizeButton.setBackground(color);
            privateToggleButton.setBackground(color);
            commentPanel.setBackground(color);
            resetComponentColors();
        }
        if (contextMenu != null) {
            ((MarkupAnnotationPopupMenu) contextMenu).refreshColorMenu();
        }
    }

    @Override
    public void dragEnter(DropTargetDragEvent dtde) {
        if (!isDragAcceptable(dtde)) {
            dtde.rejectDrag();
        }
    }

    @Override
    public void dragOver(DropTargetDragEvent dtde) {

    }

    @Override
    public void dropActionChanged(DropTargetDragEvent dtde) {
        if (!isDragAcceptable(dtde)) {
            dtde.rejectDrag();
        }
    }

    @Override
    public void dragExit(DropTargetEvent dte) {

    }

    /**
     * When a file is dropped on this component, the respective file's extension is used to find a FileDropHandler that
     * is registered with the AnnotationFileDropHandler.  If a FileDropHandler is found for the extension then the
     * file is passed to the FileDropHandler object along with the instnce of the annotation for processing.
     *
     * @param dropTargetDropEvent drop target drop event
     */
    @Override
    public void drop(DropTargetDropEvent dropTargetDropEvent) {
        try {
            // check to make sure that event type is ok
            if (!isDropAcceptable(dropTargetDropEvent)) {
                dropTargetDropEvent.rejectDrop();
                return;
            }
            // accept the drop action, must do this to proceed
            dropTargetDropEvent.acceptDrop(DnDConstants.ACTION_COPY);
            Transferable transferable = dropTargetDropEvent.getTransferable();
            DataFlavor[] flavors = transferable.getTransferDataFlavors();
            for (DataFlavor dataFlavor : flavors) {
                // Check to see if a file was dropped on the viewer frame
                if (dataFlavor.equals(DataFlavor.javaFileListFlavor)) {
                    List fileList = (List) transferable.getTransferData(dataFlavor);
                    // load all the files that where dragged
                    for (Object aFileList : fileList) {
                        File file = (File) aFileList;
                        AnnotationFileDropHandler annotationFileDropHandler = AnnotationFileDropHandler.getInstance();
                        annotationFileDropHandler.handlePopupAnnotationFileDrop(file, this);
                    }
                }
            }
            dropTargetDropEvent.dropComplete(true);

        } catch (IOException ioe) {
            logger.log(Level.FINE, "IO exception during file drop", ioe);
        } catch (UnsupportedFlavorException ufe) {
            logger.log(Level.FINE, "Drag and drop not supported", ufe);
        }
    }

    // Utility method which alows copy or move drag actions
    private boolean isDragAcceptable(DropTargetDragEvent event) {
        // check to make sure that we only except the copy action
        return (event.getDropAction() & DnDConstants.ACTION_COPY_OR_MOVE) != 0;
    }

    // Utility method which allows copy or move drop actions
    private boolean isDropAcceptable(DropTargetDropEvent event) {
        // check to make sure that we only except the copy action
        return (event.getDropAction() & DnDConstants.ACTION_COPY_OR_MOVE) != 0;
    }

    /**
     * Some work is needed here to get xor painting for darker background colors and black text.
     */
    private Color checkColor(Color newColor) {
        return newColor;
    }

    protected Color calculateContrastColor(int rgb) {
        int tolerance = 120;
        if (Math.abs(((rgb) & 0xFF) - 0x80) <= tolerance &&
                Math.abs(((rgb >> 8) & 0xFF) - 0x80) <= tolerance &&
                Math.abs(((rgb >> 16) & 0xFF) - 0x80) <= tolerance) {
            return new Color((0x7F7F7F + rgb) & 0xFFFFFF);
        } else {
            return new Color(rgb ^ 0xFFFFFF);
        }

    }

    public void setTextAreaFontSize(float size) {
        float textAreaFontSize = annotation.getTextAreaFontsize();
        annotation.setTextAreaFontsize(size);
        DocumentViewModel documentViewModel = documentViewController.getDocumentViewModel();
        PopupAnnotation.updateTextAreaFontSize(textArea, textAreaFontSize, documentViewModel.getViewZoom());
    }

    public void setHeaderLabelsFontSize(float size) {
        float headerLabelsFontSize = annotation.getHeaderLabelsFontSize();
        annotation.setHeaderLabelsFontSize(size);
        DocumentViewModel documentViewModel = documentViewController.getDocumentViewModel();
        PopupAnnotation.updateTextAreaFontSize(titleLabel, headerLabelsFontSize, documentViewModel.getViewZoom());
        PopupAnnotation.updateTextAreaFontSize(creationLabel, headerLabelsFontSize, documentViewModel.getViewZoom());
    }

    public void changeFontSize(float changeValue) {
        setTextAreaFontSize(annotation.getTextAreaFontsize() + changeValue);
        setHeaderLabelsFontSize(annotation.getHeaderLabelsFontSize() + changeValue);
    }

    public MarkupGlueComponent getMarkupGlueComponent() {
        return markupGlueComponent;
    }

    public void setMarkupGlueComponent(MarkupGlueComponent markupGlueComponent) {
        this.markupGlueComponent = markupGlueComponent;
    }

    public void setFontSize(float size) {
        setTextAreaFontSize(size);
        setHeaderLabelsFontSize(size);
    }

    public int getFontSize() {
        return textArea.getFont().getSize();
    }

    class PopupTreeListener extends MouseAdapter {
        @Override
        public void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isRightMouseButton(e)) {
                int row = commentTree.getClosestRowForLocation(e.getX(), e.getY());
                commentTree.setSelectionRow(row);
                contextMenu.show(e.getComponent(), e.getX(), e.getY());
            }
        }
    }

    private static class MarkupAnnotationTreeNode extends DefaultMutableTreeNode {

        private MarkupAnnotationTreeNode(MarkupAnnotation annot) {
            super(annot);
        }

        @Override
        public String toString() {
            final MarkupAnnotation annot = (MarkupAnnotation) userObject;
            if (annot != null) {
                return annot.getTitleText() + " - " + annot.getContents();
            } else {
                return "";
            }
        }

        @Override
        public MarkupAnnotationTreeNode clone() {
            return (MarkupAnnotationTreeNode) super.clone();
        }
    }

}