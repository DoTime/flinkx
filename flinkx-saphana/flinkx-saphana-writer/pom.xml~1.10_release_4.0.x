<?xml version="1.0" encoding="UTF-8"?>
<project xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xmlns="http://maven.apache.org/POM/4.0.0"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>flinkx-saphana</artifactId>
        <groupId>com.dtstack.flinkx</groupId>
        <version>1.6</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <artifactId>flinkx-saphana-writer</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.dtstack.flinkx</groupId>
            <artifactId>flinkx-saphana-core</artifactId>
            <version>1.6</version>
        </dependency>
        <dependency>
            <groupId>com.dtstack.flinkx</groupId>
            <artifactId>flinkx-rdb-writer</artifactId>
            <version>1.6</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <artifactSet>
                                <excludes>
                                    <exclude>org.slf4j:slf4j-api</exclude>
                                    <exclude>log4j:log4j</exclude>
                                    <exclude>ch.qos.logback:*</exclude>
                                </excludes>
                            </artifactSet>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                            <relocations>
                                <relocation>
                                    <pattern>io.netty</pattern>
                                    <shadedPattern>shade.saphanawriter.io.netty</shadedPattern>
                                </relocation>
                                <relocation>
                                    <pattern>com.google</pattern>
                                    <shadedPattern>shade.saphanawriter.com.google</shadedPattern>
                                </relocation>
                            </relocations>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <artifactId>maven-antrun-plugin</artifactId>
                <version>1.2</version>
                <executions>
                    <execution>
                        <id>copy-resources</id>
                        <!-- here the phase you need -->
                        <phase>package</phase>
                        <goals>
                            <goal>run</goal>
                        </goals>
                        <configuration>
                            <tasks>
                                <copy todir="${basedir}/../../syncplugins/saphanawriter">
                                    <fileset dir="target/">
                                        <include name="${project.name}-${project.version}.jar" />
                                    </fileset>
                                </copy>
                                <!--suppress UnresolvedMavenProperty -->
                                <move file="${basedir}/../../syncplugins/saphanawriter/${project.name}-${project.version}.jar"
                                      tofile="${basedir}/../../syncplugins/saphanawriter/${project.name}-${git.branch}.jar" />
                            </tasks>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>