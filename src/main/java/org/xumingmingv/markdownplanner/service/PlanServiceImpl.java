package org.xumingmingv.markdownplanner.service;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import org.xumingmingv.markdownplanner.model.IProject;
import org.xumingmingv.markdownplanner.model.Project;
import org.xumingmingv.markdownplanner.model.SummaryProject;
import org.xumingmingv.markdownplanner.model.task.AtomicTask;
import org.xumingmingv.markdownplanner.model.task.Task;
import org.xumingmingv.markdownplanner.parser.Parser;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.xumingmingv.markdownplanner.Utils;

@Service("planService")
public class PlanServiceImpl implements PlanService {
    private static final Logger LOG = LoggerFactory.getLogger(PlanServiceImpl.class);
    @Autowired
    private CacheService<IProject> projectCacheService;
    @Autowired
    private ConfigService configService;
    public IProject getProject(String filePath) {
        return getProject(filePath, null, null, null, false);
    }

    public IProject getProject(String filePath, String man, String status, List<String> keywords, boolean reverse) {
        // get from cache
        IProject fullProject = null;
        if (configService.isCacheEnabled()) {
            fullProject = projectCacheService.get(filePath);
        }

        if (fullProject == null) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Read project(" + filePath + ") from disk.");
            }

            if (SummaryProject.isSummaryProject(filePath)) {
                File parentDir = new File(filePath).getParentFile();
                File[] subFiles = parentDir.listFiles();
                List<File> planFiles = Arrays.stream(subFiles)
                    .filter(f -> f.getAbsolutePath().endsWith(".plan.md"))
                    .collect(Collectors.toList());
                SummaryProject summaryProject = new SummaryProject();
                planFiles.stream()
                    .forEach(f -> {
                        summaryProject.addProject(readProjectFromDisk(f.getAbsolutePath()));
                    });

                fullProject = summaryProject;
            } else {
                fullProject = readProjectFromDisk(filePath);

                // 只有具体的project才加入缓存
                projectCacheService.set(filePath, fullProject);
            }
        }

        return filterProject(fullProject, man, status, keywords, reverse);
    }

    private Project readProjectFromDisk(String filePath) {
        Project fullProject;
        List<String> lines = Utils.readFile(filePath);

        Parser parser = new Parser();
        fullProject = parser.parse(lines);
        return fullProject;
    }

    @Override
    public void updateTaskProgress(String filePath, String name, int oldProgress, int newProgress, int lineNumber) {
        IProject project = getProject(filePath);
        if (project == null) {
            throw new IllegalArgumentException("No such project: " + filePath);
        }

        // 搜索出指定行号的任务
        List<Task> tasks = project.getTasks().stream()
            .filter(t -> !t.isComposite())
            .filter(t -> {
                AtomicTask atomicTask = (AtomicTask) t;
                return atomicTask.getLineNumber() == lineNumber;
            }).collect(Collectors.toList());

        if (tasks.isEmpty() || tasks.size() > 1) {
            throw new IllegalArgumentException("No task or more than one task has the lineNumber: " + lineNumber);
        }

        AtomicTask targetTask = (AtomicTask) tasks.get(0);
        if (!targetTask.getName().equals(name) || targetTask.getProgress() != oldProgress) {
            throw new IllegalArgumentException("Project is modified after you last fetch! Try to refresh the page & try again");
        }

        List<String> lines = Utils.readFile(filePath);
        String targetLine = lines.get(lineNumber - 1);

        String newTargetLine;
        // 原来的计划里面因为progress为0所以压根就没有写
        if (oldProgress == 0 && !targetLine.contains("[" + oldProgress + "%]")) {
            newTargetLine = targetLine.trim() + "[" + newProgress + "%]";
        } else {
            newTargetLine = targetLine.replaceAll("\\[" + oldProgress + "%]", "[" + newProgress + "%]");
        }

        lines.set(lineNumber - 1, newTargetLine);
        Utils.writeFile(filePath, Joiner.on("\n").join(lines));
    }

    private IProject filterProject(IProject fullProject, String man,
        String status, List<String> keywords, boolean reverse) {
        IProject project = fullProject;
        if (status != null) {
            switch (status) {
                case "Completed":
                    project = project.hideNotCompleted();
                    break;
                case "NotCompleted":
                    project = project.hideCompleted();
                    break;
                case "All":
                default:
                    break;
            }
        }

        if (StringUtils.isNotBlank(man)) {
            project = project.filterUser(man);
        }

        if (keywords != null && !keywords.isEmpty()) {
            project = project.filterKeywords(keywords, reverse);
        }
        return project;
    }

    public void setProjectCacheService(CacheService<IProject> projectCacheService) {
        this.projectCacheService = projectCacheService;
    }
}
