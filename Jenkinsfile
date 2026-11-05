pipeline {
    agent any
    tools { jdk 'jdk25' }
    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }
    stages {
        stage('Build and GameTests') {
            steps {
                sh 'chmod +x gradlew'
                sh './gradlew clean build -PacceptMinecraftEula=true --no-daemon --stacktrace'
            }
        }
    }
    post {
        success { archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true }
        always {
            junit allowEmptyResults: true, testResults: 'physics-core/build/test-results/test/*.xml'
            archiveArtifacts artifacts: 'build/run/gameTest/logs/*.log', allowEmptyArchive: true
        }
    }
}
