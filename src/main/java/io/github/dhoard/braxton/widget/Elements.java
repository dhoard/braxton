/*
 * Copyright (c) 2026-present Douglas Hoard
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Builds the element trees an application's view is made of.
 *
 * <p>An element tree is data: a leaf is a widget and its model, and a group is its children and
 * the axis it places them on. This class is the short way to write one, so an update body stays
 * about the model rather than about constructors. Every helper is a pure function of its arguments
 * and returns a new tree.</p>
 *
 * <p>The vocabulary replaces the {@code view} helpers an application used before the kernel:</p>
 * <ul>
 *   <li>{@code Views.text} → {@link #text}, which measures the columns it needs unless a sizing
 *       says otherwise;</li>
 *   <li>{@link #row} and {@link #column} stack children; their alignment overloads accept
 *       {@link Alignment#START}, {@link Alignment#CENTER}, or {@link Alignment#END};</li>
 *   <li>{@code Views.padding} → {@link #pad};</li>
 *   <li>{@code Views.border} → {@link #frame}, which composes the chrome and the inset;</li>
 *   <li>{@code Views.fixed}/{@code Views.grow}/{@code Views.sized} → {@link #fixed}, {@link #grow},
 *       {@link #measured}, which set the child's sizing;</li>
 *   <li>{@code Views.overlay} → {@link #stack}, or {@link #modal} for a layer that owns the
 *       screen;</li>
 *   <li>{@code Views.blank} → {@link #spacer}, a leaf that paints nothing and takes its share.</li>
 * </ul>
 */
public final class Elements {

    private Elements() {}

    /**
     * Styled text, wrapped to the columns it is given.
     *
     * @param <Msg> the message type of the tree
     * @param value the text; null is empty
     * @return the element
     */
    public static <Msg> Element<Msg> text(StyledText value) {
        return new Element.Leaf<StyledText, Msg>(Text.<Msg>of(), value, Sizing.measured());
    }

    /**
     * Styled text measured with a width policy.
     *
     * @param <Msg> the message type of the tree
     * @param value the text; null is empty
     * @param policy how a cluster nobody has assigned a width is measured; required
     * @return the element
     */
    public static <Msg> Element<Msg> text(StyledText value, WidthPolicy policy) {
        return new Element.Leaf<StyledText, Msg>(Text.<Msg>of(policy), value, Sizing.measured());
    }

    /**
     * Plain text in one style, wrapped to the columns it is given.
     *
     * @param <Msg> the message type of the tree
     * @param value the text; null is empty
     * @param style the style to paint it in; null for none
     * @return the element
     */
    public static <Msg> Element<Msg> text(String value, Style style) {
        return text(StyledText.of(value == null ? "" : value, style));
    }

    /**
     * Plain text in no particular style, wrapped to the columns it is given.
     *
     * @param <Msg> the message type of the tree
     * @param value the text; null is empty
     * @return the element
     */
    public static <Msg> Element<Msg> text(String value) {
        return text(value, Style.NONE);
    }

    /**
     * Children placed left to right, each as wide as it measures.
     *
     * @param <Msg> the message type of the tree
     * @param items the non-null children, in paint order; a null array is empty
     * @return the element
     */
    @SafeVarargs
    public static <Msg> Element<Msg> row(Element<Msg>... items) {
        List<Element<Msg>> children = new ArrayList<>(items == null ? 0 : items.length);
        if (items != null) {
            for (Element<Msg> item : items) children.add(item);
        }
        return new Element.Group<>(children, Element.Direction.ROW, Alignment.START);
    }

    /**
     * Children placed top to bottom, each as tall as it measures.
     *
     * @param <Msg> the message type of the tree
     * @param items the non-null children, in paint order; a null array is empty
     * @return the element
     */
    @SafeVarargs
    public static <Msg> Element<Msg> column(Element<Msg>... items) {
        List<Element<Msg>> children = new ArrayList<>(items == null ? 0 : items.length);
        if (items != null) {
            for (Element<Msg> item : items) children.add(item);
        }
        return new Element.Group<>(children, Element.Direction.COLUMN, Alignment.START);
    }

    /**
     * Children placed left to right, aligned on the cross axis.
     *
     * @param <Msg> the message type of the tree
     * @param align where a child shorter than the row sits
     * @param items the non-null children, in paint order; a null array is empty
     * @return the element
     */
    @SafeVarargs
    public static <Msg> Element<Msg> row(Alignment align, Element<Msg>... items) {
        List<Element<Msg>> children = new ArrayList<>(items == null ? 0 : items.length);
        if (items != null) {
            for (Element<Msg> item : items) children.add(item);
        }
        return new Element.Group<>(children, Element.Direction.ROW, align);
    }

    /**
     * Children placed top to bottom, aligned on the cross axis.
     *
     * @param <Msg> the message type of the tree
     * @param align where a child narrower than the column sits
     * @param items the non-null children, in paint order; a null array is empty
     * @return the element
     */
    @SafeVarargs
    public static <Msg> Element<Msg> column(Alignment align, Element<Msg>... items) {
        List<Element<Msg>> children = new ArrayList<>(items == null ? 0 : items.length);
        if (items != null) {
            for (Element<Msg> item : items) children.add(item);
        }
        return new Element.Group<>(children, Element.Direction.COLUMN, align);
    }

    /**
     * Children placed top to bottom, each as tall as it measures.
     *
     * @param <Msg> the message type of the tree
     * @param items the non-null children, in paint order; a null array is empty
     * @return the element
     */
    public static <Msg> Element<Msg> column(List<Element<Msg>> items) {
        return new Element.Group<>(items, Element.Direction.COLUMN, Alignment.START);
    }

    /**
     * Children placed left to right, each as wide as it measures.
     *
     * @param <Msg> the message type of the tree
     * @param items the non-null children, in paint order; a null array is empty
     * @return the element
     */
    public static <Msg> Element<Msg> row(List<Element<Msg>> items) {
        return new Element.Group<>(items, Element.Direction.ROW, Alignment.START);
    }

    /**
     * A child inside the insets that surround it.
     *
     * @param <Msg> the message type of the tree
     * @param insets the space kept clear on each side of the child
     * @param child the child to place; required
     * @return the element
     */
    public static <Msg> Element<Msg> pad(Insets insets, Element<Msg> child) {
        return new Element.Pad<>(insets, child);
    }

    /**
     * A child inside an inset that is the same on all four sides.
     *
     * @param <Msg> the message type of the tree
     * @param all the space kept clear on every side; never negative
     * @param child the child to place; required
     * @return the element
     */
    public static <Msg> Element<Msg> pad(int all, Element<Msg> child) {
        return pad(new Insets(all, all, all, all), child);
    }

    /**
     * A child framed by a border, with the border's own inset around it.
     *
     * <p>The chrome and the content are two layers of a stack: the frame paints the rectangle and
     * answers no pointer, the content sits one cell inside it. Nothing has to agree on a size,
     * because the frame fills what it is given.</p>
     *
     * @param <Msg> the message type of the tree
     * @param border the border characters; required
     * @param child the content to frame; required
     * @return the element
     */
    public static <Msg> Element<Msg> frame(Border border, Element<Msg> child) {
        return frame(border, StyledText.of("", Style.NONE), Style.NONE, child);
    }

    /**
     * A framed child with a title on the top edge.
     *
     * @param <Msg> the message type of the tree
     * @param border the border characters; required
     * @param title the title; null or empty for none
     * @param style the style of the border and the title
     * @param child the content to frame; required
     * @return the element
     */
    public static <Msg> Element<Msg> frame(Border border, StyledText title, Style style, Element<Msg> child) {
        Element<Msg> chrome = new Element.Leaf<Frame.Model, Msg>(
                Frame.<Msg>of(), Frame.Model.of(border).withTitle(title).withStyle(style));
        // Only a visible border takes a cell on each side: an invisible one draws no chrome, so
        // insetting the content for it would be a frame nobody can see.
        Insets insets = border != null && border.isVisible() ? new Insets(1, 1, 1, 1) : Insets.NONE;
        return new Element.Stack<>(List.of(chrome, pad(insets, child)));
    }

    /**
     * A child framed by a border whose top row the application builds.
     *
     * <p>The builder is given the columns the frame resolved and returns the row to paint there, so
     * a tab bar can hand its tabs and the opening between them to the frame that surrounds the
     * content below: the tabs, the frame and the page stay aligned at any width because one function
     * decides the row. The remaining edges are the border's, so the content is inset exactly as
     * {@link #frame} insets it.</p>
     *
     * @param <Msg> the message type of the tree
     * @param border the border characters; required
     * @param style the style of the edges; null for none
     * @param top builds the top row from the available columns; null draws no top row of its own
     * @param child the content to frame; required
     * @return the element
     */
    public static <Msg> Element<Msg> topEdge(
            Border border, Style style, IntFunction<StyledText> top, Element<Msg> child) {
        Element<Msg> chrome = new Element.Leaf<Frame.Model, Msg>(
                Frame.<Msg>of(), Frame.Model.of(border).withStyle(style).withTopEdge(top == null ? null : top::apply));
        return new Element.Stack<>(List.of(chrome, pad(new Insets(1, 1, 1, 1), child)));
    }

    /**
     * Children that share one rectangle, painted in declaration order.
     *
     * @param <Msg> the message type of the tree
     * @param layers the layers, bottom first
     * @return the element
     */
    @SafeVarargs
    public static <Msg> Element<Msg> stack(Element<Msg>... layers) {
        List<Element<Msg>> children = new ArrayList<>(layers.length);
        for (Element<Msg> layer : layers) children.add(layer);
        return new Element.Stack<>(children);
    }

    /**
     * A layer that owns the screen while it is present: only it can be hit or take focus.
     *
     * @param <Msg> the message type of the tree
     * @param layer the layer's content; required
     * @return the element
     */
    public static <Msg> Element<Msg> modal(Element<Msg> layer) {
        return new Element.Modal<>(layer);
    }

    /**
     * A leaf that paints nothing, so the space it takes is the space it was given.
     *
     * @param <Msg> the message type of the tree
     * @return the element
     */
    public static <Msg> Element<Msg> spacer() {
        return new Element.Leaf<Spacer.Model, Msg>(Spacer.<Msg>of(), Spacer.Model.NONE);
    }

    /**
     * A background that paints every cell of the rectangle it is given.
     *
     * @param <Msg> the message type of the tree
     * @param style the style painted across the fill; null for none
     * @return the element
     */
    public static <Msg> Element<Msg> fill(Style style) {
        return new Element.Leaf<Style, Msg>(Fill.<Msg>of(), style == null ? Style.NONE : style);
    }

    /**
     * A vertical scrollbar over a scroll position the application owns.
     *
     * <p>The bar is one column and as many rows as its window, so it is placed beside the pane it
     * scrolls and mapped into the application's messages:</p>
     *
     * <pre>{@code
     * Elements.row(Elements.grow(pane), Elements.scrollbar(bar, state).map(Msg.Scrolled::new))
     * }</pre>
     *
     * <p>Its keys, pointer gestures and wheel are the bar's own, and what they produce is a
     * {@link Scrollbar.Msg} the application stores or translates. The bar is held by the
     * application rather than made here, so one instance per placed bar survives every frame - which
     * is what keeps a drag with the bar it started on.</p>
     *
     * @param bar the bar; required, and one instance per placement
     * @param model the position to paint; required
     * @return the element, emitting the bar's own messages
     */
    public static Element<Scrollbar.Msg> scrollbar(Scrollbar bar, Scrollbar.Model model) {
        return Objects.requireNonNull(bar, "bar").element(model);
    }

    /**
     * Two panes and the divider between them.
     *
     * <p>The first pane takes the cells the model names, the divider takes one, and the second pane
     * takes what is left, along the axis the model's direction names. The panes are the caller's
     * elements, composed rather than rebuilt.</p>
     *
     * <p>The divider is held by the application rather than made here, so one instance per placed
     * split survives every frame - which is what keeps a drag with the divider it started on.</p>
     *
     * @param <Msg> the message type of the tree
     * @param divider the divider; required, and one instance per placement
     * @param model the split to paint; required
     * @param first the first pane's content; required
     * @param second the second pane's content; required
     * @param messages maps the divider's messages into the tree's message type; required
     * @return the element
     */
    public static <Msg> Element<Msg> splitPane(
            SplitPane divider,
            SplitPane.Model model,
            Element<Msg> first,
            Element<Msg> second,
            Function<? super SplitPane.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(divider, "divider").element(model, first, second, messages);
    }

    /**
     * A hierarchy of rows the user expands, collapses and selects.
     *
     * <p>The tree is a component, so its messages are the tree's own and the application maps them
     * into its message type:</p>
     *
     * <pre>{@code
     * Elements.tree(files, state.files(), Msg.FilesChanged::new)
     * }</pre>
     *
     * <p>A press on a branch's marker toggles it, a press on a row selects it, and {@code enter} on a
     * leaf reports {@link Tree.Output.Chosen}. The tree is held by the application rather than made
     * here, so one instance per placed tree survives every frame - which is what keeps a pointer
     * gesture with the tree it started on.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param tree the tree; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the tree's messages into the tree's parent's message type; required
     * @return the element
     */
    public static <Msg> Element<Msg> tree(
            Tree tree, Tree.Model model, Function<? super Tree.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(tree, "tree").element(model).map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A vertical menu of items the user highlights and chooses.
     *
     * <p>The menu is a component, so its messages are the menu's own and the application maps them
     * into its message type:</p>
     *
     * <pre>{@code
     * Elements.menu(actions, state.menu(), Msg.MenuChanged::new)
     * }</pre>
     *
     * <p>A press on a choosable item chooses it, {@code enter} chooses the highlighted one, and
     * {@code esc} asks the application to dismiss the menu. The menu is held by the application rather
     * than made here, so one instance per placed menu survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param menu the menu; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the menu's messages into the composed tree's message type; required
     * @return the element
     */
    public static <Msg> Element<Msg> menu(
            Menu menu, Menu.Model model, Function<? super Menu.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(menu, "menu").element(model).map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A filterable list of commands the user narrows by typing and chooses by pressing enter.
     *
     * <p>The palette is a component, so its messages are the palette's own and the application maps
     * them into its message type:</p>
     *
     * <pre>{@code
     * Elements.commandPalette(commands, state.palette(), Msg.PaletteChanged::new)
     * }</pre>
     *
     * <p>The palette is held by the application rather than made here, so one instance per placed
     * palette survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param palette the palette; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the palette's messages into the composed tree's message type; required
     * @return the element
     */
    public static <Msg> Element<Msg> commandPalette(
            CommandPalette palette,
            CommandPalette.Model model,
            Function<? super CommandPalette.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(palette, "palette")
                .element(model)
                .map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A single choice field: the chosen option, and the list it opens onto.
     *
     * <p>The select is a component, so its messages are the select's own and the application maps
     * them into its message type:</p>
     *
     * <pre>{@code
     * Elements.select(sort, state.sort(), Msg.SortChanged::new)
     * }</pre>
     *
     * <p>Closed, an arrow chooses the previous or the next option and reports it; {@code enter} and
     * {@code space} open the list. Open, the arrows move the highlight, {@code enter} chooses it and
     * {@code esc} closes the list again. A press on the field opens or closes the list and a press on
     * an option chooses it. The select is held by the application rather than made here, so one
     * instance per placed field survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param select the select; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the select's messages into the composed tree's message type; required
     * @return the element
     */
    public static <Msg> Element<Msg> select(
            Select select, Select.Model model, Function<? super Select.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(select, "select")
                .element(model)
                .map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A multiple choice field: the options the user marked, and the list it marks them in.
     *
     * <p>The multi-select is a component, so its messages are its own and the application maps them
     * into its message type:</p>
     *
     * <pre>{@code
     * Elements.multiSelect(tags, state.tags(), Msg.TagsChanged::new)
     * }</pre>
     *
     * <p>Closed, {@code enter} and {@code space} open the list. Open, the arrows move the highlight,
     * {@code space} and {@code enter} mark or unmark the highlighted option and {@code esc} closes the
     * list. A press on the field opens or closes the list and a press on an option marks it, leaving
     * the list open so several options can be marked. The multi-select is held by the application
     * rather than made here, so one instance per placed field survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param multiSelect the multi-select; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the multi-select's messages into the composed tree's message type; required
     * @return the element
     */
    public static <Msg> Element<Msg> multiSelect(
            MultiSelect multiSelect,
            MultiSelect.Model model,
            Function<? super MultiSelect.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(multiSelect, "multiSelect")
                .element(model)
                .map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A checkbox: one label, one box, one yes or no.
     *
     * <p>The checkbox is a component, so its messages are its own and the application maps them into
     * its message type:</p>
     *
     * <pre>{@code
     * Elements.checkbox(remember, state.remember(), Msg.RememberChanged::new)
     * }</pre>
     *
     * <p>{@code space} and a left press on the row flip the box. The checkbox is held by the
     * application rather than made here, so one instance per placed box survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param checkbox the checkbox; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the checkbox's messages into the composed tree's message type; required
     * @return the element
     */
    public static <Msg> Element<Msg> checkbox(
            Checkbox checkbox, Checkbox.Model model, Function<? super Checkbox.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(checkbox, "checkbox")
                .element(model)
                .map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A radio group: a column of options of which exactly one is chosen.
     *
     * <p>The group is a component, so its messages are its own and the application maps them into its
     * message type:</p>
     *
     * <pre>{@code
     * Elements.radioGroup(size, state.size(), Msg.SizeChanged::new)
     * }</pre>
     *
     * <p>An arrow or a press chooses an option and reports it, and {@code space} or {@code enter}
     * chooses the option that is already chosen. The group is held by the application rather than made
     * here, so one instance per placed group survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param group the group; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the group's messages into the composed tree's message type; required
     * @return the element
     */
    public static <Msg> Element<Msg> radioGroup(
            RadioGroup group, RadioGroup.Model model, Function<? super RadioGroup.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(group, "group").element(model).map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * An ordered set of labeled fields with one validation policy and one answer to a submission.
     *
     * <p>The form is a component, so its messages are the form's own and the application maps them
     * into its message type:</p>
     *
     * <pre>{@code
     * Elements.form(
     *         form,
     *         state.form(),
     *         Map.of(
     *                 "name", name.view(state.name(), context).map(Msg.Name::new),
     *                 "email", email.view(state.email(), context).map(Msg.Email::new)),
     *         Msg.FormChanged::new)
     * }</pre>
     *
     * <p>The application keeps each field's widget instance and model, because the kernel has one
     * focused leaf: the form places the editors and owns the traversal, the errors and the
     * submission, and it never takes a key or a press away from the field that is editing. A press on
     * a field's label focuses that field, {@code tab} and {@code shift+tab} move focus when the
     * focused field declines them, and a submission reports either {@link Form.Output.Submitted} or
     * {@link Form.Output.Rejected}. The form is held by the application rather than made here, so one
     * instance per placed form survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param form the form; required, and one instance per placement
     * @param model the state to paint; required
     * @param fields each field's editor, by field id; null places none
     * @param messages maps the form's messages into the composed tree's message type; required
     * @return the element, emitting the composed messages
     */
    public static <Msg> Element<Msg> form(
            Form form,
            Form.Model model,
            Map<String, Element<Msg>> fields,
            Function<? super Form.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(form, "form")
                .element(model, fields, Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A transient notification that goes away on its own.
     *
     * <p>A toast paints one row while it shows and nothing while it does not, so it can be placed in
     * a column without reserving a row it is not using:</p>
     *
     * <pre>{@code
     * Elements.column(status, Elements.toast(saved, state.saved()))
     * }</pre>
     *
     * <p>{@code Msg.Show} starts its time, a press on it dismisses it, and it reports {@link
     * Toast.Output.Expired} once when its time runs out. A toast owns no keys and no focus, so it
     * never takes the keyboard from what the user is doing. The toast is held by the application
     * rather than made here, so one instance per placed toast survives every frame.</p>
     *
     * @param toast the toast; required, and one instance per placement
     * @param model the state to paint; required
     * @return the element, emitting the toast's own messages
     */
    public static Element<Toast.Msg> toast(Toast toast, Toast.Model model) {
        return Objects.requireNonNull(toast, "toast").element(model);
    }

    /**
     * An ordered, bounded set of notifications the user reads, marks and chooses.
     *
     * <p>The center is a component, so its messages are its own and the application maps them into
     * its message type:</p>
     *
     * <pre>{@code
     * Elements.notificationCenter(events, state.events(), Msg.EventsChanged::new)
     * }</pre>
     *
     * <p>The newest notification is first, the window follows it while the reader is at it, and
     * {@code enter} on the selected notification reports {@link NotificationCenter.Output.Chosen}.
     * The center is held by the application rather than made here, so one instance per placed center
     * survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param center the center; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the center's messages into the composed tree's message type; required
     * @return the element, emitting the composed messages
     */
    public static <Msg> Element<Msg> notificationCenter(
            NotificationCenter center,
            NotificationCenter.Model model,
            Function<? super NotificationCenter.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(center, "center")
                .element(model)
                .map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A modal dialog: a titled box in the middle of the layer, the application's content, and the row
     * of buttons that answers it.
     *
     * <p>The returned element is the whole modal layer, so it is placed over the scene it interrupts
     * and nothing under it can be hit or take focus while it is there:</p>
     *
     * <pre>{@code
     * Elements.stack(
     *         page,
     *         Elements.dialog(sync, state.dialog(), Elements.text("Apply the changes?"), Msg.DialogChanged::new))
     * }</pre>
     *
     * <p>The content is composed, never rebuilt, so a field inside the dialog keeps the instance that
     * owns its caret. {@code enter} and {@code space} activate the button the keys are on, {@code
     * left}, {@code right}, {@code tab} and {@code shift+tab} move between the buttons, a press on a
     * button activates it, and {@code esc} asks the application to dismiss the dialog. The dialog is
     * held by the application rather than made here, so one instance per placed dialog survives every
     * frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param dialog the dialog; required, and one instance per placement
     * @param model the state to paint; required
     * @param content the content inside the box; null paints none
     * @param messages maps the dialog's messages into the composed tree's message type; required
     * @return the element, emitting the composed messages
     */
    public static <Msg> Element<Msg> dialog(
            Dialog dialog,
            Dialog.Model model,
            Element<Msg> content,
            Function<? super Dialog.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(dialog, "dialog")
                .element(model, content, Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A line chart: one or more series drawn in braille, with a reader that says which sample it is
     * on.
     *
     * <p>The chart is a component, so its messages are its own and the application maps them into its
     * message type:</p>
     *
     * <pre>{@code
     * Elements.lineChart(chart, state.traffic(), Msg.TrafficChanged::new)
     * }</pre>
     *
     * <p>A chart fills the rectangle it is placed in, so in a column it takes the share the
     * application gives it - {@code Elements.grow} or {@code Elements.fixed} - the way a fill does.
     * {@code left}, {@code right}, {@code home} and {@code end} move the reader, a press or a drag
     * puts the guide under the pointer, and every gesture that lands on a sample reports {@link
     * LineChart.Output.Inspected}. The chart is held by the application rather than made here, so one
     * instance per placed chart survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param chart the chart; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the chart's messages into the composed tree's message type; required
     * @return the element, emitting the composed messages
     */
    public static <Msg> Element<Msg> lineChart(
            LineChart chart, LineChart.Model model, Function<? super LineChart.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(chart, "chart").element(model).map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A bar chart: one labeled bar per category in a window the model owns, with a reader.
     *
     * <p>The chart is a component, so its messages are its own and the application maps them into its
     * message type:</p>
     *
     * <pre>{@code
     * Elements.barChart(chart, state.disk(), Msg.DiskChanged::new)
     * }</pre>
     *
     * <p>{@code up}, {@code down}, {@code home} and {@code end} move the reader, a press or a drag
     * selects the row under the pointer, and every gesture that lands on a category reports {@link
     * BarChart.Output.Inspected}. The chart is held by the application rather than made here, so one
     * instance per placed chart survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param chart the chart; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the chart's messages into the composed tree's message type; required
     * @return the element, emitting the composed messages
     */
    public static <Msg> Element<Msg> barChart(
            BarChart chart, BarChart.Model model, Function<? super BarChart.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(chart, "chart").element(model).map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A sparkline: a one-row trend with a reader that says which sample the caret is on.
     *
     * <p>The sparkline is a component, so its messages are its own and the application maps them into
     * its message type:</p>
     *
     * <pre>{@code
     * Elements.sparkline(trend, state.load(), Msg.LoadChanged::new)
     * }</pre>
     *
     * <p>{@code left}, {@code right}, {@code home} and {@code end} move the reader, a press or a drag
     * puts it under the pointer, and every gesture that lands on a sample reports {@link
     * Sparkline.Output.Inspected}. The sparkline is held by the application rather than made here, so
     * one instance per placement survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param sparkline the sparkline; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the sparkline's messages into the composed tree's message type; required
     * @return the element, emitting the composed messages
     */
    public static <Msg> Element<Msg> sparkline(
            Sparkline sparkline, Sparkline.Model model, Function<? super Sparkline.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(sparkline, "sparkline")
                .element(model)
                .map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A gauge: one value on its range, as a filled bar with a readout.
     *
     * <pre>{@code
     * Elements.gauge(usage, Gauge.Model.of(state.load(), 0.0, 4.0))
     * }</pre>
     *
     * <p>A gauge is an indicator: it answers no keys, takes no turn in the focus walk and is not a
     * pointer target, so placing one never takes a gesture from what surrounds it. The gauge is held
     * by the application rather than made here, so one instance per placement survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param gauge the gauge; required, and one instance per placement
     * @param model the reading to paint; required
     * @return the element
     */
    public static <Msg> Element<Msg> gauge(Gauge<Msg> gauge, Gauge.Model model) {
        return Objects.requireNonNull(gauge, "gauge").element(model);
    }

    /**
     * A calendar: a month grid over a selected date, with a reader that moves a day, a week or a
     * month at a time.
     *
     * <p>The calendar is a component, so its messages are its own and the application maps them into
     * its message type:</p>
     *
     * <pre>{@code
     * Elements.calendar(calendar, state.due(), Msg.DueChanged::new)
     * }</pre>
     *
     * <p>The month painted is the selected date's month; {@code left}, {@code right}, {@code up},
     * {@code down}, {@code pageup}, {@code pagedown}, {@code home} and {@code end} move the
     * selection, {@code enter} chooses it and reports {@link Calendar.Output.Chosen}, a press
     * selects a painted day and a second press chooses it. The calendar is held by the application
     * rather than made here, so one instance per placed calendar survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param calendar the calendar; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the calendar's messages into the composed tree's message type; required
     * @return the element, emitting the composed messages
     */
    public static <Msg> Element<Msg> calendar(
            Calendar calendar, Calendar.Model model, Function<? super Calendar.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(calendar, "calendar")
                .element(model)
                .map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A log: an append-only, bounded list of lines that follows the tail while the reader is at it.
     *
     * <p>The log is a component, so its messages are its own and the application maps them into its
     * message type:</p>
     *
     * <pre>{@code
     * Elements.logViewer(log, state.trace(), Msg.TraceChanged::new)
     * }</pre>
     *
     * <p>{@code up}, {@code down}, {@code pageup}, {@code pagedown}, {@code home} and {@code end}
     * move the reader, {@code end} resumes following the tail, {@code enter} reports the selected
     * line as {@link LogViewer.Output.Chosen}, a press selects a line and a second press chooses it,
     * and the wheel moves one line. The log is held by the application rather than made here, so one
     * instance per placed log survives every frame.</p>
     *
     * @param <Msg> the message type of the composed tree
     * @param viewer the log; required, and one instance per placement
     * @param model the state to paint; required
     * @param messages maps the log's messages into the composed tree's message type; required
     * @return the element, emitting the composed messages
     */
    public static <Msg> Element<Msg> logViewer(
            LogViewer viewer, LogViewer.Model model, Function<? super LogViewer.Msg, ? extends Msg> messages) {
        return Objects.requireNonNull(viewer, "viewer")
                .element(model)
                .map(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * A spacer with a fixed main-axis size.
     *
     * @param <Msg> the message type of the tree
     * @param cells the cells to occupy; never negative
     * @return the element
     */
    public static <Msg> Element<Msg> spacer(int cells) {
        return new Element.Leaf<Spacer.Model, Msg>(Spacer.<Msg>of(), Spacer.Model.NONE, Sizing.fixed(cells));
    }

    /**
     * A child that takes a fixed number of cells on its parent's main axis.
     *
     * @param <Msg> the message type of the tree
     * @param cells the cells to occupy; never negative
     * @param child the child; required
     * @return the element
     */
    public static <Msg> Element<Msg> fixed(int cells, Element<Msg> child) {
        return withSizing(child, Sizing.fixed(cells));
    }

    /**
     * A child that takes an equal share of the main axis left over.
     *
     * @param <Msg> the message type of the tree
     * @param child the child; required
     * @return the element
     */
    public static <Msg> Element<Msg> grow(Element<Msg> child) {
        return withSizing(child, Sizing.grow());
    }

    /**
     * A child that takes a share of the main axis in proportion to a weight.
     *
     * @param <Msg> the message type of the tree
     * @param weight the share of the leftover space; positive
     * @param child the child; required
     * @return the element
     */
    public static <Msg> Element<Msg> grow(int weight, Element<Msg> child) {
        return withSizing(child, Sizing.grow(weight));
    }

    /**
     * A child that asks for exactly what it measures.
     *
     * @param <Msg> the message type of the tree
     * @param child the child; required
     * @return the element
     */
    public static <Msg> Element<Msg> measured(Element<Msg> child) {
        return withSizing(child, Sizing.measured());
    }

    /**
     * Applies a sizing to any element, including a group.
     */
    private static <Msg> Element<Msg> withSizing(Element<Msg> element, Sizing sizing) {
        return switch (element) {
            case Element.Leaf<?, Msg> leaf -> leaf.withSizing(sizing);
            case Element.Group<Msg> group -> group.withSizing(sizing);
            case Element.Stack<Msg> stack -> stack.withSizing(sizing);
            case Element.Pad<Msg> pad -> pad.withSizing(sizing);
            // A modal layer is not a sibling, so there is no main axis for it to share.
            case Element.Modal<Msg> modal -> modal;
        };
    }
}
