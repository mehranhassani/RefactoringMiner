package org.refactoringminer.rm1;

import gr.uom.java.xmi.UMLModel;
import gr.uom.java.xmi.UMLModelASTReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.refactoringminer.api.GitHistoryRefactoringMiner;
import org.refactoringminer.api.GitService;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import org.refactoringminer.api.RefactoringType;
import org.refactoringminer.util.GitServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitHistoryRefactoringMinerImpl implements GitHistoryRefactoringMiner {

	Logger logger = LoggerFactory.getLogger(GitHistoryRefactoringMinerImpl.class);
	private boolean analyzeMethodInvocations;
	private Set<RefactoringType> refactoringTypesToConsider = null;
	
	private static final OutputStream NULL_OUTPUT_STREAM = new OutputStream() {
		@Override
		public void write(int b) throws IOException {
		}
	};
	
	public GitHistoryRefactoringMinerImpl() {
		this(false);
	}

	private GitHistoryRefactoringMinerImpl(boolean analyzeMethodInvocations) {
		this.analyzeMethodInvocations = analyzeMethodInvocations;
		this.setRefactoringTypesToConsider(
			RefactoringType.RENAME_CLASS,
			RefactoringType.MOVE_CLASS,
			RefactoringType.MOVE_SOURCE_FOLDER,
			RefactoringType.RENAME_METHOD,
			RefactoringType.EXTRACT_OPERATION,
			RefactoringType.INLINE_OPERATION,
			RefactoringType.MOVE_OPERATION,
			RefactoringType.PULL_UP_OPERATION,
			RefactoringType.PUSH_DOWN_OPERATION,
			RefactoringType.MOVE_ATTRIBUTE,
			RefactoringType.PULL_UP_ATTRIBUTE,
			RefactoringType.PUSH_DOWN_ATTRIBUTE,
			RefactoringType.EXTRACT_INTERFACE,
			RefactoringType.EXTRACT_SUPERCLASS,
			RefactoringType.EXTRACT_AND_MOVE_OPERATION,
			RefactoringType.RENAME_PACKAGE
		);
	}

	public void setRefactoringTypesToConsider(RefactoringType ... types) {
		this.refactoringTypesToConsider = new HashSet<RefactoringType>();
		for (RefactoringType type : types) {
			this.refactoringTypesToConsider.add(type);
		}
	}
	
	private void detect(GitService gitService, Repository repository, final RefactoringHandler handler, Iterator<RevCommit> i) {
		int commitsCount = 0;
		int errorCommitsCount = 0;
		int refactoringsCount = 0;

		File metadataFolder = repository.getDirectory();
		File projectFolder = metadataFolder.getParentFile();
		String projectName = projectFolder.getName();
		
		long time = System.currentTimeMillis();
		while (i.hasNext()) {
			RevCommit currentCommit = i.next();
			try {
				List<Refactoring> refactoringsAtRevision = detectRefactorings(gitService, repository, handler, projectFolder, currentCommit);
				refactoringsCount += refactoringsAtRevision.size();
				
			} catch (Exception e) {
				logger.warn(String.format("Ignored revision %s due to error", currentCommit.getId().getName()), e);
				errorCommitsCount++;
			}

			commitsCount++;
			long time2 = System.currentTimeMillis();
			if ((time2 - time) > 20000) {
				time = time2;
				logger.info(String.format("Processing %s [Commits: %d, Errors: %d, Refactorings: %d]", projectName, commitsCount, errorCommitsCount, refactoringsCount));
			}
		}

		handler.onFinish(refactoringsCount, commitsCount, errorCommitsCount);
		logger.info(String.format("Analyzed %s [Commits: %d, Errors: %d, Refactorings: %d]", projectName, commitsCount, errorCommitsCount, refactoringsCount));
	}
	
	public static String getFileContent(RevCommit commit, Repository repo,String fileName) throws Exception {
		TreeWalk treeWalk  = TreeWalk.forPath( repo, fileName, commit.getTree() );
		InputStream inputStream = repo.open( treeWalk.getObjectId( 0 ), Constants.OBJ_BLOB ).openStream();
		treeWalk.close();
		String output = IOUtils.toString(inputStream, "UTF-8");
		return output;

	}
	
	public UMLModel[] getUMLModelfromBlobs(File projectFolder, RevCommit commit, Repository repo) throws Exception {
		GitService gitService = new GitServiceImpl();
		UMLModel[] UMLs = new UMLModel[2];
		List<String> filesBefore = new ArrayList<String>();
		List<String> filesCurrent = new ArrayList<String>();
		Map<String, String> renamedFilesHint = new HashMap<String, String>();
		gitService.fileTreeDiff(repo, commit, filesBefore, filesCurrent, renamedFilesHint, true);
		List<String> newFilesContent = new ArrayList<String>();
		List<String> oldFilesContent = new ArrayList<String>();
		if(!filesCurrent.isEmpty() && !filesBefore.isEmpty()){
			for (String file: filesCurrent){
				String content = getFileContent(commit, repo, file);
				newFilesContent.add(content);
			}
			for (String file: filesBefore){
				String content = getFileContent(commit.getParent(0), repo, file);
				oldFilesContent.add(content);
			}
			UMLs[1] = new UMLModelASTReader(projectFolder, filesCurrent, newFilesContent).getUmlModel();
			logger.info("UML model is ready for {}", commit.toString());
			
			UMLs[0] = new UMLModelASTReader(projectFolder, filesBefore, oldFilesContent).getUmlModel();
			logger.info("UML model is ready for {}", commit.getParent(0).toString());
			
		}

		return UMLs;

	}

	protected List<Refactoring> detectRefactorings(GitService gitService, Repository repository, final RefactoringHandler handler, File projectFolder, RevCommit currentCommit) throws Exception {
		List<Refactoring> refactoringsAtRevision;
		String commitId = currentCommit.getId().getName();
		List<String> filesBefore = new ArrayList<String>();
		List<String> filesCurrent = new ArrayList<String>();
		Map<String, String> renamedFilesHint = new HashMap<String, String>();
		gitService.fileTreeDiff(repository, currentCommit, filesBefore, filesCurrent, renamedFilesHint, true);
		// If no java files changed, there is no refactoring. Also, if there are
		// only ADD's or only REMOVE's there is no refactoring
		if (!filesBefore.isEmpty() && !filesCurrent.isEmpty()) {
			
			UMLModel[] umlModels = getUMLModelfromBlobs(projectFolder, currentCommit, repository);
			UMLModel parentUMLModel = umlModels[0];
			UMLModel currentUMLModel = umlModels[1];
			
			// Diff between currentModel e parentModel
			refactoringsAtRevision = parentUMLModel.diff(currentUMLModel, renamedFilesHint).getRefactorings();
			refactoringsAtRevision = filter(refactoringsAtRevision);
			
		} else {
			//logger.info(String.format("Ignored revision %s with no changes in java files", commitId));
			refactoringsAtRevision = Collections.emptyList();
		}
		handler.handle(commitId, refactoringsAtRevision);
		return refactoringsAtRevision;
	}

	protected List<Refactoring> detectRefactorings(final RefactoringHandler handler, File projectFolder, String cloneURL, String currentCommitId) {
		List<Refactoring> refactoringsAtRevision = Collections.emptyList();
		try {
			Properties prop = new Properties();
			InputStream input = new FileInputStream("github-credentials.properties");
			prop.load(input);
			String username = prop.getProperty("username");
			String password = prop.getProperty("password");
			List<String> filesBefore = new ArrayList<String>();
			List<String> filesCurrent = new ArrayList<String>();
			Map<String, String> renamedFilesHint = new HashMap<String, String>();
			String parentCommitId = populateWithGitHubAPI(cloneURL, currentCommitId, username, password, filesBefore, filesCurrent, renamedFilesHint);
			File currentFolder = new File(projectFolder.getParentFile(), projectFolder.getName() + "-" + currentCommitId);
			File parentFolder = new File(projectFolder.getParentFile(), projectFolder.getName() + "-" + parentCommitId);
			if (currentFolder.exists() && parentFolder.exists()) {
				UMLModel currentUMLModel = createModel(currentFolder, filesCurrent);
				UMLModel parentUMLModel = createModel(parentFolder, filesBefore);
				// Diff between currentModel e parentModel
				refactoringsAtRevision = parentUMLModel.diff(currentUMLModel, renamedFilesHint).getRefactorings();
				refactoringsAtRevision = filter(refactoringsAtRevision);
			}
			else {
				logger.warn(String.format("Folder %s not found", currentFolder.getPath()));
			}
		} catch (Exception e) {
			logger.warn(String.format("Ignored revision %s due to error", currentCommitId), e);
			handler.handleException(currentCommitId, e);
		}
		handler.handle(currentCommitId, refactoringsAtRevision);
		return refactoringsAtRevision;
	}

	private String populateWithGitHubAPI(String cloneURL, String currentCommitId, String username, String password,
			List<String> filesBefore, List<String> filesCurrent, Map<String, String> renamedFilesHint) throws IOException {
		String parentCommitId = null;
		GitHub gitHub = null;
		if (username != null && password != null) {
			gitHub = GitHub.connectUsingPassword(username, password);
		}
		else {
			gitHub = GitHub.connect();
		}
		//https://github.com/ is 19 chars
		String repoName = cloneURL.substring(19, cloneURL.indexOf(".git"));
		GHRepository repository = gitHub.getRepository(repoName);
		GHCommit commit = repository.getCommit(currentCommitId);
		parentCommitId = commit.getParents().get(0).getSHA1();
		List<GHCommit.File> commitFiles = commit.getFiles();
		for (GHCommit.File commitFile : commitFiles) {
			if (commitFile.getFileName().endsWith(".java")) {
				if (commitFile.getStatus().equals("modified")) {
					filesBefore.add(commitFile.getFileName());
					filesCurrent.add(commitFile.getFileName());
				}
				else if (commitFile.getStatus().equals("added")) {
					filesCurrent.add(commitFile.getFileName());
				}
				else if (commitFile.getStatus().equals("removed")) {
					filesBefore.add(commitFile.getFileName());
				}
				else if (commitFile.getStatus().equals("renamed")) {
					filesBefore.add(commitFile.getPreviousFilename());
					filesCurrent.add(commitFile.getFileName());
					renamedFilesHint.put(commitFile.getPreviousFilename(), commitFile.getFileName());
				}
			}
		}
		return parentCommitId;
	}

	protected List<Refactoring> filter(List<Refactoring> refactoringsAtRevision) {
		if (this.refactoringTypesToConsider == null) {
			return refactoringsAtRevision;
		}
		List<Refactoring> filteredList = new ArrayList<Refactoring>();
		for (Refactoring ref : refactoringsAtRevision) {
			if (this.refactoringTypesToConsider.contains(ref.getRefactoringType())) {
				filteredList.add(ref);
			}
		}
		return filteredList;
	}
	
	@Override
	public void detectAll(Repository repository, String branch, final RefactoringHandler handler) throws Exception {
		GitService gitService = new GitServiceImpl() {
			@Override
			public boolean isCommitAnalyzed(String sha1) {
				return handler.skipCommit(sha1);
			}
		};
		RevWalk walk = gitService.createAllRevsWalk(repository, branch);
		try {
 			detect(gitService, repository, handler, walk.iterator());
		} finally {
			walk.dispose();
		}
	}

	@Override
	public void fetchAndDetectNew(Repository repository, final RefactoringHandler handler) throws Exception {
		GitService gitService = new GitServiceImpl() {
			@Override
			public boolean isCommitAnalyzed(String sha1) {
				return handler.skipCommit(sha1);
			}
		};
		RevWalk walk = gitService.fetchAndCreateNewRevsWalk(repository);
		try {
			detect(gitService, repository, handler, walk.iterator());
		} finally {
			walk.dispose();
		}
	}

	protected UMLModel createModel(File projectFolder, List<String> files) throws Exception {
		return new UMLModelASTReader(projectFolder, files).getUmlModel();
	}

	@Override
	public void detectAtCommit(Repository repository, String cloneURL, String commitId, RefactoringHandler handler) {
		File metadataFolder = repository.getDirectory();
		File projectFolder = metadataFolder.getParentFile();
		GitService gitService = new GitServiceImpl();
		RevWalk walk = new RevWalk(repository);
		try {
			RevCommit commit = walk.parseCommit(repository.resolve(commitId));
			walk.parseCommit(commit.getParent(0));
			this.detectRefactorings(gitService, repository, handler, projectFolder, commit);
		} catch (MissingObjectException moe) {
			this.detectRefactorings(handler, projectFolder, cloneURL, commitId);
		} catch (Exception e) {
			logger.warn(String.format("Ignored revision %s due to error", commitId), e);
			handler.handleException(commitId, e);
		} finally {
			walk.dispose();
		}
	}

	@Override
	public String getConfigId() {
	    return "RM1";
	}
}
