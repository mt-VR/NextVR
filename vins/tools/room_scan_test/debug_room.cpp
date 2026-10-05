#include <cstdio>
#include <cmath>
#include <vector>
#include <random>
#include <algorithm>
#include <fstream>
#include "runtime/vins_room.h"

int main(){
    vins::RoomScanner s;
    s.beginScan(2.0);  // short window so the debug scan finishes quickly
    static std::mt19937 rng(123);
    const double halfX=2.4, halfY=2.0, floorY=-1.6;
    const double eyeHeight=1.6; // eye about 1.6 m above floor
    std::vector<int> ids;
    std::vector<Eigen::Vector3d> pts;
    ids.reserve(1024); pts.reserve(1024);
    // Camera is at origin, looking down -z, with y up.
    // Floor sits at z=floorY (below eye, so negative).
    // Points are stored in camera-frame: (x, y, z).
    auto emit=[&](double x,double y,double z){
        ids.push_back((int)ids.size()+1);
        pts.push_back(Eigen::Vector3d(x, y, z));
    };
    // floor points
    for(int i=0;i<800;i++){
        const double u=(rng()/(double)rng.max()*2.0-1.0)*halfX;
        const double v=(rng()/(double)rng.max()*2.0-1.0)*halfY;
        emit(u, v, floorY);
    }
    // walls: four strips of height from floor up past eye level.
    const double wallTop = eyeHeight * 2.0;
    for(int i=0;i<700;i++){
        double top=floorY + (rng()/(double)rng.max()*wallTop);
        // right wall: x=+halfX, y in [-halfY,+halfY]
        emit(halfX, (rng()/(double)rng.max()*2.0-1.0)*halfY, top);
        // left wall: x=-halfX
        emit(-halfX,(rng()/(double)rng.max()*2.0-1.0)*halfY, top);
        // far wall: y=+halfY
        emit((rng()/(double)rng.max()*2.0-1.0)*halfX, halfY, top);
        // near wall: y=-halfY
        emit((rng()/(double)rng.max()*2.0-1.0)*halfX, -halfY, top);
    }
    // table: a cluster of points sitting on the floor (must NOT steal the floor)
    for(int i=0;i<400;i++){
        double u=(rng()/(double)rng.max()*2.0-1.0)*0.6;
        double v=(rng()/(double)rng.max()*2.0-1.0)*0.5;
        emit(u*halfX, v*halfY, floorY + 0.75);
    }
    // quick check: how many points are below floorY (the floor plane)?
    int belowFloor = 0;
    for(const auto &p : pts)        if(p.z() < floorY - 0.1) belowFloor++;
    printf("built %zu points, %d below floorY (%.2f)\n", pts.size(), belowFloor, floorY);
    // Write first 200 points to a CSV for later inspection.
    {
      FILE *f = fopen("/tmp/room_pts.csv", "w");
      if(f){ fprintf(f, "x,y,z\n"); for(size_t i=0; i<std::min(size_t(200), pts.size()); i++) fprintf(f, "%.4f,%.4f,%.4f\n", pts[i].x(), pts[i].y(), pts[i].z()); fclose(f); }
    }
    for(int f=0; f<30; f++){
        std::vector<int> fids; std::vector<Eigen::Vector3d> fpts;
        const size_t start=(f*73)%pts.size();
        const size_t cnt=std::min(size_t(100), pts.size());
        for(size_t i=0;i<cnt;i++){
            const size_t idx=(start+i)%pts.size();
            fids.push_back(ids[idx]);
            fpts.push_back(pts[idx]);
        }
        s.addFrame(fids, fpts, Eigen::Vector3d::Zero(), 0.1*f);
        auto &sc = s.scan();
        double elapsed = s.elapsed();
        printf("frame %d: count=%d state=%d progress=%.2f done=%d elapsed=%.2f\n",
               f, (int)sc.pointCount, (int)sc.state,
               sc.progress, (int)s.done(), elapsed);
        if(s.done()) break;
    }
    auto &scan=s.scan();
    printf("FINAL: valid=%d pointCount=%d floorFound=%d walls=%zd quantiles w=%zu\n",
           scan.valid, scan.pointCount, scan.floorFound,
           scan.wallNormals.size(), scan.quantiles.size());
    if(scan.floorFound){
        printf("  floor normal=(%.2f,%.2f,%.2f) height=%.2f radius=%.2f center=(%.2f,%.2f,%.2f)\n",
               scan.floorNormal.x(),scan.floorNormal.y(),scan.floorNormal.z(),
               scan.floorHeight, scan.floorRadius, scan.floorCentre.x(),scan.floorCentre.y(),scan.floorCentre.z());
    } else {
        printf("  NO FLOOR FOUND\n");
    }
    return 0;
}
