pipeline {
    agent { label 'built-in' }
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
        stage('Repeat server GameTests') {
            steps {
                sh 'for run in 1 2 3 4 5; do rm -rf build/run/gameTest/world; ./gradlew runGameTest -PacceptMinecraftEula=true --no-daemon || exit 1; done'
            }
        }
        stage('Client GameTests') {
            options { timeout(time: 8, unit: 'MINUTES') }
            steps {
                sh '''git archive HEAD | docker run -i --rm -e LIBGL_ALWAYS_SOFTWARE=1 -e JAVA_TOOL_OPTIONS=-Drenderpearl.vulkan.allowcpudevice=true -w /workspace eclipse-temurin:25-jdk sh -c '
                    tar -xf - &&
                    apt-get update -qq &&
                    apt-get install -y --no-install-recommends xvfb xauth libgl1 libgl1-mesa-dri libglx-mesa0 libvulkan1 mesa-vulkan-drivers &&
                    xvfb-run -a -s "-screen 0 1280x1024x24 +extension GLX +render -noreset" ./gradlew runClientGameTest -PacceptMinecraftEula=true --no-daemon --stacktrace
                ' '''
            }
        }
        stage('Packaged server GameTests') {
            steps {
                sh './gradlew runProductionGameTest -PacceptMinecraftEula=true --no-daemon --stacktrace'
            }
        }
        stage('Physics benchmark') {
            steps {
                sh './gradlew :physics-core:benchmark --no-daemon --stacktrace'
            }
        }
    }
    post {
        success { archiveArtifacts artifacts: 'build/libs/*.jar', excludes: 'build/libs/*-gametest.jar', fingerprint: true }
        always {
            junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml'
            archiveArtifacts artifacts: 'build/run/gameTest/logs/*.log,build/run/clientGameTest/logs/*.log,build/run/productionGameTest/logs/*.log', allowEmptyArchive: true
        }
    }
}
