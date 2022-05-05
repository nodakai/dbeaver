/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.erd.ui.router;


import org.eclipse.draw2dl.Connection;
import org.eclipse.draw2dl.IFigure;
import org.eclipse.draw2dl.geometry.Point;
import org.eclipse.draw2dl.geometry.PointList;
import org.eclipse.draw2dl.geometry.PrecisionPoint;
import org.eclipse.draw2dl.geometry.Rectangle;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.Pair;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mikami-Tabuchi’s Algorithm
 * 1. Expand horizontal and vertical line from source to target
 * 2. In every iteration, expand from the last expanded line by STEP_SIZE
 * 3. Continue until a line from source intersects another line from target
 * 4. Backtrace from interception
 */
//possible optimizations
//By the rules of math parallel lines couldn't collide, so we need to check only perpendicular lines of opposite source/target origin
//multi-dimensional arrays for trial lines?
public class MikamiTabuchiRouter {

    private int spacing = 15;
    private final List<Rectangle> obstacles = new ArrayList<>();
    private PrecisionPoint start, finish;
    private final List<OrthogonalPath> workingPaths = new ArrayList<>();
    private final List<OrthogonalPath> userPaths = new ArrayList<>();
    private final Map<OrthogonalPath, List<OrthogonalPath>> pathsToChildPaths = new HashMap<>();
    //Increase for performance, increasing this parameter lowers accuracy.
    private static final int STEP_SIZE = 5;

    private Set<Point> pointSet;

    private static final int SOURCE_VERTICAL_LINES = 0;
    private static final int SOURCE_HORIZONTAL_LINES = 1;
    private static final int TARGET_VERTICAL_LINES = 2;
    private static final int TARGET_HORIZONTAL_LINES = 3;

    private Map<Integer, Map<Integer, List<TrialLine>>> linesMap;

    //In worst case scenarios line search may become laggy,
    //if after this amount iterations nothing was found -> stop
    private static final int MAX_LINE_COUNT = 200000;
    private int currentLineCount;


    IFigure clientArea;

    public void setClientArea(IFigure clientArea) {
        this.clientArea = clientArea;
    }

    private Pair<TrialLine, TrialLine> result;

    private void createLinesFromTrial(TrialLine pos, int iter) {
        float from = pos.vertical ? pos.from.y : pos.from.x;
        float start = pos.start;
        float end = pos.finish;
        for (float i = (pos.hasForbiddenStart() ? pos.creationForbiddenStart - 1 : from); i >= start; i -= STEP_SIZE) {
            currentLineCount++;
            if (createTrial(pos, iter, i)) {
                break;
            }
            if (currentLineCount > MAX_LINE_COUNT) {
                return;
            }
        }
        for (float i = (pos.hasForbiddenFinish() ? pos.creationForbiddenFinish + 1 : from); i < end; i += STEP_SIZE) {
            currentLineCount++;
            if (createTrial(pos, iter, i)) {
                break;
            }
            if (currentLineCount > MAX_LINE_COUNT) {
                return;
            }
        }
    }

    private boolean createTrial(TrialLine pos, int iter, float i) {
        TrialLine trialLine = createTrialLine(i, !pos.vertical, pos);
        if (trialLine == null) {
            return false;
        }
        getLinesMap(trialLine, iter).add(trialLine);
        final TrialLine interception = trialLine.findIntersection();
        // We found needed line, finish execution
        if (interception != null) {
            if (pointSet.contains(getInterceptionPoint(trialLine, interception))) {
                return false;
            }
            if (result == null) {
                result = new Pair<>(trialLine, interception);
                return true;
            } else {
                Pair<TrialLine, TrialLine> trialLinePair = new Pair<>(trialLine, interception);
                result = calculateDistance(result) >= calculateDistance(trialLinePair) ? trialLinePair : result;
            }
        }
        return false;
    }

    boolean pointLiesOnPreviouslyCreatedPath(Point point) {
        for (OrthogonalPath workingPath : workingPaths) {
            if (workingPath.getPoints() != null && workingPath.getPoints().polylineContainsPoint(point.x, point.y, 2)) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private List<TrialLine> getLinesMap(TrialLine line, int iteration) {
        if (line.vertical) {
            return line.fromSource ? linesMap.get(iteration).get(SOURCE_VERTICAL_LINES) : linesMap.get(iteration).get(TARGET_VERTICAL_LINES);
        } else {
            return line.fromSource ? linesMap.get(iteration).get(SOURCE_HORIZONTAL_LINES) : linesMap.get(iteration).get(TARGET_HORIZONTAL_LINES);
        }
    }

    @NotNull
    private List<TrialLine> getOpposingLinesMap(TrialLine line, int iteration) {
        if (line.vertical) {
            return line.fromSource ? linesMap.get(iteration).get(TARGET_HORIZONTAL_LINES) : linesMap.get(iteration).get(SOURCE_HORIZONTAL_LINES);
        } else {
            return line.fromSource ? linesMap.get(iteration).get(TARGET_VERTICAL_LINES) : linesMap.get(iteration).get(SOURCE_VERTICAL_LINES);
        }
    }

    private PrecisionPoint getInterceptionPoint(TrialLine source, TrialLine target) {
        if (source.vertical) {
            return new PrecisionPoint(source.from.x, target.from.y);
        } else {
            return new PrecisionPoint(target.from.x, source.from.y);
        }
    }

    @Nullable
    private TrialLine createTrialLine(float pos, boolean vertical, @NotNull TrialLine parentLine) {
        final TrialLine trialLine;
        PrecisionPoint point;
        if (vertical) {
            point = new PrecisionPoint(pos, parentLine.from.y);
        } else {
            point = new PrecisionPoint(parentLine.from.x, pos);
        }
        if (pointSet.contains(point) || pointLiesOnPreviouslyCreatedPath(point)) {
            return null;
        }
        trialLine = new TrialLine(point, parentLine);
        return trialLine;
    }

    public void setSpacing(int spacing) {
        this.spacing = spacing;
    }

    public boolean updateObstacle(Rectangle rectangle, Rectangle newBounds) {
        boolean result = obstacles.remove(rectangle);
        result |= obstacles.add(newBounds);
        return result;
    }

    public void addObstacle(Rectangle bounds) {
        obstacles.add(bounds);
    }

    public boolean removeObstacle(Rectangle bounds) {
        return obstacles.remove(bounds);
    }

    private PointList traceback(Pair<TrialLine, TrialLine> res) {
        PointList points = new PointList();
        TrialLine line = res.getFirst();
        PrecisionPoint point = null;
        while (line != null) {
            if (point == null || !point.equals(line.from)) {
                points.addPoint(line.from);
            }
            point = line.from;
            pointSet.add(point);
            line = line.getParent();
        }
        points.reverse();
        point = getInterceptionPoint(res.getFirst(), res.getSecond());
        pointSet.add(point);
        points.addPoint(point);
        line = res.getSecond();
        while (line != null) {
            if (!line.from.equals(point)) {
                points.addPoint(line.from);
            }
            point = line.from;
            pointSet.add(point);
            line = line.getParent();
        }
        return points;
    }

    public List<OrthogonalPath> solve() {
        pointSet = new HashSet<>();
        updateChildPaths();
        for (OrthogonalPath userPath : workingPaths.stream().filter(OrthogonalPath::isDirty).collect(Collectors.toList())) {
            final PointList pointList = solvePath(userPath);
            userPath.setPoints(pointList);
        }
        recombineChildrenPaths();
        return Collections.unmodifiableList(userPaths);
    }

    private void updateChildPaths() {
        for (OrthogonalPath path : userPaths) {
            if (path.isDirty()) {
                List<OrthogonalPath> children = this.pathsToChildPaths.get(path);
                int previousCount = 1;
                int newCount = 1;
                if (children == null) {
                    children = new ArrayList<>();
                } else {
                    previousCount = children.size();
                }
                if (path.getBendpoints() != null) {
                    newCount = path.getBendpoints().size() + 1;
                }

                if (previousCount != newCount) {
                    children = this.regenerateChildPaths(path, children, previousCount, newCount, path.getConnection());
                }

                this.refreshChildrenEndpoints(path, children);
            }
        }
    }

    private void refreshChildrenEndpoints(OrthogonalPath path, List<OrthogonalPath> children) {
        Point previous = path.getStart();
        PointList bendPoints = path.getBendpoints();

        for (int i = 0; i < children.size(); ++i) {
            Point next;
            if (i < bendPoints.size()) {
                next = bendPoints.getPoint(i);
            } else {
                next = path.getEnd();
            }
            OrthogonalPath child = children.get(i);
            child.setStartPoint(previous);
            child.setEndPoint(next);
            previous = next;
        }
        Point previousPointFinish;
        for (int i = 1; i < children.size() - 1; i++) {
            previousPointFinish = children.get(i - 1).end;
            children.get(i).updateForbiddenDirection(previousPointFinish);
        }
    }

    private List<OrthogonalPath> regenerateChildPaths(OrthogonalPath path, List<OrthogonalPath> orthogonalPaths, int currentCount, int newCount, Connection connection) {
        if (currentCount == 1) {
            this.workingPaths.remove(path);
            currentCount = 0;
            orthogonalPaths = new ArrayList<>();
            this.pathsToChildPaths.put(path, orthogonalPaths);
        } else if (newCount == 1) {
            this.workingPaths.removeAll(orthogonalPaths);
            this.workingPaths.add(path);
            this.pathsToChildPaths.remove(path);
            return Collections.EMPTY_LIST;
        }

        OrthogonalPath child;
        while (currentCount < newCount) {
            child = new OrthogonalPath(connection);
            orthogonalPaths.add(child);
            this.workingPaths.add(child);
            ++currentCount;
        }
        while (currentCount > newCount) {
            child = orthogonalPaths.remove(orthogonalPaths.size() - 1);
            this.workingPaths.remove(child);
            --currentCount;
        }

        return orthogonalPaths;
    }

    private double calculateDistance(Pair<TrialLine, TrialLine> res) {
        double distance = 0;
        PointList traceback = traceback(res);
        for (int i = 0; i < traceback.size() - 1; i++) {
            Point first = traceback.getPoint(i);
            Point second = traceback.getPoint(i + 1);
            distance += first.getDistance(second);
        }
        return distance;
    }

    @Nullable
    private PointList solvePath(OrthogonalPath path) {
        if (path.getStart().equals(path.getEnd())) {
            PointList pointList = new PointList();
            pointList.addPoint(path.getStart());
            pointList.addPoint(path.getEnd());
            return pointList;
        }
        //Client are
        if (!clientArea.getClientArea().contains(path.start) || !clientArea.getClientArea().contains(path.end)) {
            clientArea.getUpdateManager().performUpdate();
        }
        linesMap = new HashMap<>();
        this.start = new PrecisionPoint(path.start);
        result = null;
        this.finish = new PrecisionPoint(path.end);
        int iter = 0;
        currentLineCount = 0;
        initStartingTrialLines(path.isChild(), path.getForbiddenDirection());
        while (result == null && currentLineCount < MAX_LINE_COUNT) {
            linesMap.put(iter + 1, new HashMap<>());
            initNewLayer(iter + 1);
            for (int i = 0; i < 4; i++) {
                for (TrialLine trialLine : linesMap.get(iter).get(i)) {
                    createLinesFromTrial(trialLine, iter + 1);
                    if (currentLineCount > MAX_LINE_COUNT) {
                        PointList pointList = new PointList();
                        pointList.addPoint(start);
                        pointList.addPoint(finish);
                        return pointList;
                    }
                    if (result != null) {
                        return traceback(result);
                    }
                }
            }
            iter++;
        }
        return null;
    }


    private void recombineChildrenPaths() {

        for (OrthogonalPath path : this.pathsToChildPaths.keySet()) {
            path.getPoints().removeAllPoints();
            List<OrthogonalPath> childPaths = this.pathsToChildPaths.get(path);
            OrthogonalPath childPath = null;

            for (OrthogonalPath orthogonalPath : childPaths) {
                childPath = orthogonalPath;
                path.getPoints().addAll(childPath.getPoints());
                path.getPoints().removePoint(path.getPoints().size() - 1);
            }

            path.getPoints().addPoint(childPath.getPoints().getLastPoint());
        }

    }

    private void initStartingTrialLines(boolean child, OrthogonalPath.Direction forbiddenDirection) {
        //Deviation from an original algorithm, we want only lines what connect with point horizontally
        linesMap.put(0, new HashMap<>());
        initNewLayer(0);
        final TrialLine horizontalStartTrial = new TrialLine(start, true, false, forbiddenDirection);
        final TrialLine horizontalFinishTrial = new TrialLine(finish, false, false, forbiddenDirection);
        if (child) {
            final TrialLine verticalStartTrial = new TrialLine(start, true, true, forbiddenDirection);
            final TrialLine verticalFinishTrial = new TrialLine(finish, false, true, forbiddenDirection);
            linesMap.get(0).get(SOURCE_VERTICAL_LINES).add(verticalStartTrial);
            linesMap.get(0).get(TARGET_VERTICAL_LINES).add(verticalFinishTrial);
        }
        linesMap.get(0).get(SOURCE_HORIZONTAL_LINES).add(horizontalStartTrial);
        linesMap.get(0).get(TARGET_HORIZONTAL_LINES).add(horizontalFinishTrial);
    }

    /**
     * inits list for each type of
     * @param iter number of algorithm iteration
     */
    private void initNewLayer(int iter) {
        for (int i = 0; i < 4; i++) {
            linesMap.get(iter).put(i, new ArrayList<>());
        }
    }

    public void removePath(OrthogonalPath path) {
        this.userPaths.remove(path);
        List<OrthogonalPath> orthogonalPaths = this.pathsToChildPaths.get(path);
        if (orthogonalPaths == null) {
            this.workingPaths.remove(path);
        } else {
            this.workingPaths.remove(path);
        }
    }

    public void addPath(OrthogonalPath path) {
        this.workingPaths.add(path);
        this.userPaths.add(path);
    }

    private class TrialLine {

        float start = Integer.MIN_VALUE;
        float finish = Integer.MIN_VALUE;
        boolean fromSource;

        int creationForbiddenStart = Integer.MIN_VALUE;
        int creationForbiddenFinish = Integer.MIN_VALUE;
        final PrecisionPoint from;


        boolean vertical;

        //Starting line is always inside figure, we don't want to create trial line inside it
        private void calculateForbiddenRange(OrthogonalPath.Direction forbiddenDirection) {
            for (Rectangle it : obstacles) {
                if (isInsideFigure(it, true)) {
                    if (vertical) {
                        creationForbiddenStart = it.getTop().y - spacing;
                        creationForbiddenFinish = it.getBottom().y + spacing;
                    } else {
                        creationForbiddenStart = it.getLeft().x - spacing;
                        creationForbiddenFinish = it.getRight().x + spacing;
                    }
                }
            }
            if (forbiddenDirection != null) {
                switch (forbiddenDirection) {
                    case DOWN:
                        if (vertical) {
                            creationForbiddenStart = this.from.y + spacing;
                        }
                        break;
                    case UP:
                        if (vertical) {
                            creationForbiddenFinish = this.from.y - spacing;
                        }
                        break;
                    case LEFT:
                        if (!vertical) {
                            creationForbiddenStart = this.from.x - spacing;
                        }
                        break;
                    case RIGHT:
                        if (!vertical) {
                            creationForbiddenFinish = this.from.x + spacing;
                        }
                        break;
                }
            }
        }

        public boolean hasForbiddenStart() {
            return creationForbiddenStart != Integer.MIN_VALUE;
        }

        public boolean hasForbiddenFinish() {
            return creationForbiddenFinish != Integer.MIN_VALUE;
        }

        @Nullable
        TrialLine parent;

        TrialLine(PrecisionPoint start, @NotNull TrialLine parent) {
            this.from = start;
            this.parent = parent;
            this.fromSource = parent.fromSource;
            this.vertical = !parent.vertical;
            cutByObstacles(false);
        }

        TrialLine(PrecisionPoint start, boolean fromSource, boolean vertical, OrthogonalPath.Direction forbiddenDirection) {
            this.from = start;
            this.vertical = vertical;
            this.fromSource = fromSource;
            this.cutByObstacles(true);
            this.calculateForbiddenRange(forbiddenDirection);
        }

        private boolean isInsideFigure(Rectangle it, boolean ignoreOffset) {
            int offset = spacing;
            if (ignoreOffset) {
                offset = 0;
            }
            return (it.getLeft().x - offset <= from.x && it.getRight().x + offset  > from.x
                && it.getTop().y - offset <= from.y && it.getBottom().y + offset  > from.y);
        }

        private void cutByObstacles(boolean startingLine) {
            //Check if object is on axis with line, if it is, reduce line size
            for (Rectangle it : obstacles) {
                if (isInsideFigure(it, false)) {
                    if (startingLine) {
                        continue;
                    } else {
                        cut(it);
                    }
                }
                if (vertical && it.getLeft().x - spacing <= from.x && it.getRight().x + spacing > from.x
                    || !vertical && it.getTop().y - spacing <= from.y && it.getBottom().y + spacing > from.y) {
                    //object is below need to cut start
                    cut(it);
                }
            }
            if (finish == Integer.MIN_VALUE) {
                if (vertical) {
                    finish = clientArea.getClientArea().getBottom().y;
                } else {
                    finish = clientArea.getClientArea().getRight().x;
                }
            }
            if (start == Integer.MIN_VALUE) {
                start = vertical ? clientArea.getClientArea().getTop().y : clientArea.getClientArea().getLeft().x;
            }
        }

        private void cut(Rectangle bound) {
            int fromPosition = vertical ? from.y : from.x;
            int startPoint = vertical ? bound.getTop().y : bound.getLeft().x;
            int endPoint = vertical ? bound.getBottom().y : bound.getRight().x;
            if (fromPosition > endPoint) {
                if (start == Integer.MIN_VALUE || start < endPoint + spacing) {
                    start = endPoint + spacing;
                }
            }
            if (fromPosition <= startPoint) {
                if (finish == Integer.MIN_VALUE || finish > startPoint - spacing) {
                    finish = startPoint - spacing;
                }
            }
        }

        @Nullable
        public TrialLine findIntersection() {
            for (int i = linesMap.values().size() - 1; i >= 0; i--) {
                for (TrialLine trialLine : getOpposingLinesMap(this, i)) {
                    if (intersect(trialLine)) {
                        return trialLine;
                    }
                }
            }
            return null;
        }

        private boolean intersect(TrialLine line) {
            int firstLinePos = vertical ? from.x : from.y;
            int secondLinePos = vertical ? line.from.y : line.from.x;

            return firstLinePos >= line.start && firstLinePos < line.finish && secondLinePos >= start && secondLinePos < finish;
        }

        @Nullable
        public TrialLine getParent() {
            return parent;
        }
    }
}