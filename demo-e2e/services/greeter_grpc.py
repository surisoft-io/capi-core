"""
Dummy gRPC Greeter service for CAPI Gateway demo.

Serves greeter.Greeter/SayHello with gRPC reflection enabled.
Proto stubs are compiled at container startup (see docker-compose command).
"""

from concurrent import futures
import grpc
from grpc_reflection.v1alpha import reflection

import greeter_pb2
import greeter_pb2_grpc


class GreeterServicer(greeter_pb2_grpc.GreeterServicer):
    def SayHello(self, request, context):
        name = request.name or "World"
        return greeter_pb2.HelloReply(
            message=f"Hello, {name}! Greetings from the CAPI gRPC demo."
        )


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    greeter_pb2_grpc.add_GreeterServicer_to_server(GreeterServicer(), server)

    # Enable gRPC reflection for service discovery
    service_names = (
        greeter_pb2.DESCRIPTOR.services_by_name['Greeter'].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port('0.0.0.0:50051')
    print("Greeter gRPC server listening on :50051", flush=True)
    server.start()
    server.wait_for_termination()


if __name__ == '__main__':
    serve()
