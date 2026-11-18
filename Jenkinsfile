pipeline {
	options {
		timeout(time: 30, unit: 'MINUTES')
		buildDiscarder(logRotator(numToKeepStr: '10'))
		disableConcurrentBuilds(abortPrevious: true)
		timestamps()
	}
	agent {
		label 'built-in'
	}
	tools {
		// JavaSE-25 is the BREE of every bundle here, so an older JDK does not compile
		jdk 'Java 25 Temurin'
	}
	stages {
		stage('Build and test') {
			steps {
				// deliberately not wrapped in xvnc: the suite runs with no DISPLAY at
				// all, verified rather than assumed. useUIHarness is false and every
				// UI tool refuses cleanly without a workbench, so a display would only
				// hide a tool that had started needing one
				// the Maven repository is deliberately the shared one rather than
				// per workspace: a multibranch job gives every branch and every pull
				// request its own workspace, and each would otherwise download the
				// whole Eclipse SDK again for the Tycho target platform
				sh '''
					./mvnw clean verify \
						--batch-mode --no-transfer-progress --show-version --errors
				'''
			}
		}
	}
	post {
		always {
			// no -Dmaven.test.failure.ignore: a red build is the point of this job,
			// and this repository has already shipped a suite that ran zero tests
			// while reporting success
			junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
			// the test Eclipse writes its own log, and a failure that is not in the
			// surefire report is usually explained there
			archiveArtifacts artifacts: 'tests/*/target/work/data/.metadata/*.log', allowEmptyArchive: true
		}
		success {
			// what a build actually produces: an installable p2 repository
			archiveArtifacts artifacts: 'update-site/*/target/*.zip', allowEmptyArchive: false
		}
	}
}
